package thelm.packagedauto.tile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.security.IActionHost;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import thelm.packagedauto.api.IPackageCraftingMachine;
import thelm.packagedauto.api.IPackageItem;
import thelm.packagedauto.api.IRecipeInfo;
import thelm.packagedauto.api.IRecipeType;
import thelm.packagedauto.api.MiscUtil;
import thelm.packagedauto.api.RecipeTypeRegistry;
import thelm.packagedauto.client.gui.GuiUnpackager;
import thelm.packagedauto.container.ContainerUnpackager;
import thelm.packagedauto.energy.EnergyStorage;
import thelm.packagedauto.integration.appeng.networking.HostHelperTileUnpackager;
import thelm.packagedauto.integration.appeng.recipe.RecipeCraftingPatternHelper;
import thelm.packagedauto.inventory.InventoryUnpackager;

@Optional.InterfaceList({
	@Optional.Interface(iface="appeng.api.networking.IGridHost", modid="appliedenergistics2"),
	@Optional.Interface(iface="appeng.api.networking.security.IActionHost", modid="appliedenergistics2"),
	@Optional.Interface(iface="appeng.api.networking.crafting.ICraftingProvider", modid="appliedenergistics2")
})
public class TileUnpackager extends TileBase implements ITickable, IGridHost, IActionHost, ICraftingProvider {

	public static int energyCapacity = 5000;
	public static int energyUsage = 50;

	public final PackageTracker[] trackers = new PackageTracker[10];
	public List<IRecipeInfo> recipeList = new ArrayList<>();

	public TileUnpackager() {
		setInventory(new InventoryUnpackager(this));
		setEnergyStorage(new EnergyStorage(this, energyCapacity));
		for(int i = 0; i < trackers.length; ++i) {
			trackers[i] = new PackageTracker();
		}
	}

	@Override
	protected String getLocalizedName() {
		return I18n.translateToLocal("tile.packagedauto.unpackager.name");
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			chargeEnergy();
			if(world.getTotalWorldTime() % 8 == 0) {
				fillTrackers();
				emptyTrackers();
				if(hostHelper != null && hostHelper.isActive()) {
					hostHelper.chargeEnergy();
				}
			}
			energyStorage.updateIfChanged();
		}
	}

	protected void fillTrackers() {
		List<PackageTracker> emptyTrackers = Arrays.stream(trackers).filter(PackageTracker::isEmpty).collect(Collectors.toList());
		List<PackageTracker> nonEmptyTrackers = Lists.newArrayList(trackers);
		nonEmptyTrackers.removeAll(emptyTrackers);
		for(int i = 0; i < 9; ++i) {
			if(energyStorage.getEnergyStored() >= energyUsage) {
				ItemStack stack = inventory.getStackInSlot(i);
				if(!stack.isEmpty() && stack.getItem() instanceof IPackageItem) {
					IPackageItem packageItem = (IPackageItem)stack.getItem();
					boolean flag = false;
					for(PackageTracker tracker : nonEmptyTrackers) {
						if(tracker.tryAcceptPackage(packageItem, stack)) {
							flag = true;
							stack.shrink(1);
							if(stack.isEmpty()) {
								inventory.setInventorySlotContents(i, ItemStack.EMPTY);
							}
							energyStorage.extractEnergy(energyUsage, false);
							break;
						}
					}
					if(!flag) {
						for(PackageTracker tracker : emptyTrackers) {
							if(tracker.tryAcceptPackage(packageItem, stack)) {
								stack.shrink(1);
								if(stack.isEmpty()) {
									inventory.setInventorySlotContents(i, ItemStack.EMPTY);
								}
								energyStorage.extractEnergy(energyUsage, false);
								break;
							}
						}
					}
				}
			}
		}
	}

	protected void emptyTrackers() {
		for(PackageTracker tracker : trackers) {
			if(tracker.isFilled()) {
				if(tracker.recipe != null && tracker.toSend.isEmpty() && tracker.recipe.getRecipeType().hasMachine()) {
					for(EnumFacing facing : EnumFacing.VALUES) {
						TileEntity tile = world.getTileEntity(pos.offset(facing));
						if(tile instanceof IPackageCraftingMachine) {
							IPackageCraftingMachine machine = (IPackageCraftingMachine)tile;
							if(!machine.isBusy() && machine.acceptPackage(tracker.recipe, Lists.transform(tracker.recipe.getInputs(), ItemStack::copy), facing.getOpposite())) {
								tracker.clearRecipe();
								syncTile(false);
								markDirty();
								break;
							}
						}
					}
				}
				else {
					if(tracker.toSend.isEmpty()) {
						tracker.setupToSend();
					}
					for(EnumFacing facing : EnumFacing.VALUES) {
						TileEntity tile = world.getTileEntity(pos.offset(facing));
						if(tile != null && !(tile instanceof TilePackager) && tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite())) {
							IItemHandler itemHandler = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite());
							for(int i = 0; i < tracker.toSend.size(); ++i) {
								ItemStack stack = tracker.toSend.get(i);
								for(int slot = 0; slot < itemHandler.getSlots(); ++slot) {
									ItemStack stackRem = itemHandler.insertItem(slot, stack, false);
									if(stackRem.getCount() < stack.getCount()) {
										stack = stackRem;
									}
									if(stack.isEmpty()) {
										break;
									}
								}
								tracker.toSend.set(i, stack);
							}
							tracker.toSend.removeIf(ItemStack::isEmpty);
							if(tracker.toSend.isEmpty()) {
								tracker.clearRecipe();
								break;
							}
						}
					}
				}
			}
		}
	}

	protected void chargeEnergy() {
		int prevStored = energyStorage.getEnergyStored();
		ItemStack energyStack = inventory.getStackInSlot(10);
		if(energyStack.hasCapability(CapabilityEnergy.ENERGY, null)) {
			int energyRequest = Math.min(energyStorage.getMaxReceive(), energyStorage.getMaxEnergyStored() - energyStorage.getEnergyStored());
			energyStorage.receiveEnergy(energyStack.getCapability(CapabilityEnergy.ENERGY, null).extractEnergy(energyRequest, false), false);
			if(energyStack.getCount() <= 0) {
				inventory.setInventorySlotContents(10, ItemStack.EMPTY);
			}
		}
	}

	public HostHelperTileUnpackager hostHelper;

	@Override
	public void invalidate() {
		super.invalidate();
		if(hostHelper != null) {
			hostHelper.invalidate();
		}
	}

	@Override
	public void onLoad() {
		if(Loader.isModLoaded("appliedenergistics2")) {
			hostHelper = new HostHelperTileUnpackager(this);
		}
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public IGridNode getGridNode(AEPartLocation dir) {
		return getActionableNode();
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public AECableType getCableConnectionType(AEPartLocation dir) {
		return AECableType.SMART;
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public void securityBreak() {}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public IGridNode getActionableNode() {
		if(hostHelper == null) {
			hostHelper = new HostHelperTileUnpackager(this);
		}
		return hostHelper.getNode();
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public boolean pushPattern(ICraftingPatternDetails patternDetails, InventoryCrafting table) {
		if(!isBusy() && patternDetails instanceof RecipeCraftingPatternHelper) {
			IntList emptySlots = new IntArrayList();
			for(int i = 0; i < 9; ++i) {
				if(inventory.getStackInSlot(i).isEmpty()) {
					emptySlots.add(i);
				}
			}
			IntList requiredSlots = new IntArrayList();
			for(int i = 0; i < table.getSizeInventory(); ++i) {
				if(!table.getStackInSlot(i).isEmpty()) {
					requiredSlots.add(i);
				}
			}
			if(requiredSlots.size() > emptySlots.size()) {
				return false;
			}
			for(int i = 0; i < requiredSlots.size(); ++i) {
				inventory.setInventorySlotContents(emptySlots.getInt(i), table.getStackInSlot(requiredSlots.getInt(i)).copy());
			}
			return true;
		}
		return false;
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public boolean isBusy() {
		return Arrays.stream(trackers).noneMatch(PackageTracker::isEmpty);
	}

	@Optional.Method(modid="appliedenergistics2")
	@Override
	public void provideCrafting(ICraftingProviderHelper craftingTracker) {
		ItemStack patternStack = inventory.getStackInSlot(9);
		for(IRecipeInfo pattern : recipeList) {
			if(!pattern.getOutputs().isEmpty()) {
				craftingTracker.addCraftingOption(this, new RecipeCraftingPatternHelper(patternStack, pattern));
			}
		}
	}

	public int getScaledEnergy(int scale) {
		if(energyStorage.getMaxEnergyStored() <= 0) {
			return 0;
		}
		return scale * energyStorage.getEnergyStored() / energyStorage.getMaxEnergyStored();
	}

	@Override
	public void readSyncNBT(NBTTagCompound nbt) {
		super.readSyncNBT(nbt);
		for(int i = 0; i < trackers.length; ++i) {
			trackers[i].readFromNBT(nbt.getCompoundTag(String.format("Tracker%02d", i)));
		}
	}

	@Override
	public NBTTagCompound writeSyncNBT(NBTTagCompound nbt) {
		super.writeSyncNBT(nbt);
		for(int i = 0; i < trackers.length; ++i) {
			NBTTagCompound subNBT = new NBTTagCompound();
			trackers[i].writeToNBT(subNBT);
			nbt.setTag(String.format("Tracker%02d", i), subNBT);
		}
		return nbt;
	}

	@SideOnly(Side.CLIENT)
	@Override
	public GuiContainer getClientGuiElement(EntityPlayer player, Object... args) {
		return new GuiUnpackager(new ContainerUnpackager(player.inventory, this));
	}

	@Override
	public Container getServerGuiElement(EntityPlayer player, Object... args) {
		return new ContainerUnpackager(player.inventory, this);
	}

	public class PackageTracker {

		public IRecipeInfo recipe;
		public int amount;
		public BooleanList received = new BooleanArrayList();
		public List<ItemStack> toSend = new ArrayList<>();

		public void setRecipe(IRecipeInfo recipe) {
			this.recipe = recipe;
		}

		public void clearRecipe() {
			this.recipe = null;
			amount = 0;
			received.clear();
			if(world != null && !world.isRemote) {
				syncTile(false);
				markDirty();
			}
		}

		public boolean tryAcceptPackage(IPackageItem packageItem, ItemStack stack) {
			IRecipeInfo recipe = packageItem.getRecipeInfo(stack);
			if(recipe != null) {
				if(this.recipe == null) {
					this.recipe = recipe;
					amount = recipe.getPatterns().size();
					received.size(amount);
					received.set(packageItem.getIndex(stack), true);
					syncTile(false);
					markDirty();
					return true;
				}
				else if(this.recipe.equals(recipe)) {
					int index = packageItem.getIndex(stack);
					if(!received.get(index)) {
						received.set(index, true);
						syncTile(false);
						markDirty();
						return true;
					}
				}
			}
			return false;
		}

		public boolean isFilled() {
			if(!toSend.isEmpty()) {
				return true;
			}
			for(boolean b : received) {
				if(!b) {
					return false;
				}
			}
			return true;
		}

		public boolean isEmpty() {
			return recipe == null || !recipe.isValid();
		}

		public void setupToSend() {
			if(isEmpty() || recipe.getRecipeType().hasMachine() || !toSend.isEmpty()) {
				return;
			}
			toSend.addAll(Lists.transform(recipe.getInputs(), ItemStack::copy));
		}

		public void readFromNBT(NBTTagCompound nbt) {
			clearRecipe();
			NBTTagCompound tag = nbt.getCompoundTag("Recipe");
			IRecipeType recipeType = RecipeTypeRegistry.getRecipeType(new ResourceLocation(tag.getString("RecipeType")));
			if(recipeType != null) {
				IRecipeInfo recipe = recipeType.getNewRecipeInfo();
				recipe.readFromNBT(tag);
				if(recipe.isValid()) {
					this.recipe = recipe;
					amount = nbt.getInteger("Amount");
					received.size(amount);
					byte[] receivedArray = nbt.getByteArray("Received");
					for(int i = 0; i < received.size(); ++i) {
						received.set(i, receivedArray[i] != 0);
					}
				}
			}
			MiscUtil.loadAllItems(nbt.getTagList("ToSend", 10), toSend);
		}

		public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
			if(recipe != null) {
				NBTTagCompound tag = new NBTTagCompound();
				tag.setString("RecipeType", recipe.getRecipeType().getName().toString());
				nbt.setTag("Recipe", recipe.writeToNBT(tag));
				nbt.setInteger("Amount", amount);
				byte[] receivedArray = new byte[received.size()];
				for(int i = 0; i < received.size(); ++i) {
					receivedArray[i] = (byte)(received.getBoolean(i) ? 1 : 0);
				}
				nbt.setByteArray("Received", receivedArray);
			}
			nbt.setTag("ToSend", MiscUtil.saveAllItems(new NBTTagList(), toSend));
			return nbt;
		}
	}
}