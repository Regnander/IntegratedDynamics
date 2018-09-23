package org.cyclops.integrateddynamics.core.network;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.cyclops.commoncapabilities.api.capability.inventorystate.IInventoryState;
import org.cyclops.cyclopscore.helper.TileHelpers;
import org.cyclops.cyclopscore.ingredient.collection.diff.IngredientCollectionDiff;
import org.cyclops.cyclopscore.ingredient.collection.diff.IngredientCollectionDiffManager;
import org.cyclops.integrateddynamics.Capabilities;
import org.cyclops.integrateddynamics.GeneralConfig;
import org.cyclops.integrateddynamics.api.ingredient.IIngredientComponentStorageObservable;
import org.cyclops.integrateddynamics.api.network.IPositionedAddonsNetworkIngredients;
import org.cyclops.integrateddynamics.api.part.PartPos;
import org.cyclops.integrateddynamics.api.part.PartTarget;
import org.cyclops.integrateddynamics.api.part.PrioritizedPartPos;
import org.cyclops.integrateddynamics.core.network.diagnostics.NetworkDiagnostics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Instances of this class are able to watch ingredient positions and emit diffs.
 *
 * @author rubensworks
 */
public class IngredientObserver<T, M> {

    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(GeneralConfig.ingredientNetworkObserverThreads);

    private final IPositionedAddonsNetworkIngredients<T, M> network;

    private final Set<IIngredientComponentStorageObservable.IIndexChangeObserver<T, M>> changeObservers;
    private final TIntObjectMap<Map<PrioritizedPartPos, Integer>> observeTargetTickIntervals;
    private final TIntObjectMap<Map<PrioritizedPartPos, Integer>> observeTargetTicks;
    private final TIntObjectMap<Map<PrioritizedPartPos, IngredientCollectionDiffManager<T, M>>> channeledDiffManagers;

    private final TIntObjectMap<List<PrioritizedPartPos>> lastRemoved;
    private final Map<PartPos, Integer> lastInventoryStates;

    public IngredientObserver(IPositionedAddonsNetworkIngredients<T, M> network) {
        this.network = network;
        this.changeObservers = Sets.newIdentityHashSet();
        this.observeTargetTickIntervals = new TIntObjectHashMap<>();
        this.observeTargetTicks = new TIntObjectHashMap<>();
        this.channeledDiffManagers = new TIntObjectHashMap<>();
        this.lastRemoved = new TIntObjectHashMap<>();
        this.lastInventoryStates = Maps.newHashMap();
    }

    public IPositionedAddonsNetworkIngredients<T, M> getNetwork() {
        return network;
    }

    public void onPositionRemoved(int channel, PrioritizedPartPos pos) {
        List<PrioritizedPartPos> positions = this.lastRemoved.get(channel);
        if (positions == null) {
            positions = Lists.newLinkedList();
            this.lastRemoved.put(channel, positions);
        }
        positions.add(pos);
    }

    /**
     * Add an observer for listing to index change events.
     * @param observer An index change observer.
     */
    public synchronized void addChangeObserver(IIngredientComponentStorageObservable.IIndexChangeObserver<T, M> observer) {
        changeObservers.add(observer);
    }

    /**
     * Remove the given index change observer.
     * This will silently fail if the given observer was not registered.
     * @param observer An index change observer.
     */
    public synchronized void removeChangeObserver(IIngredientComponentStorageObservable.IIndexChangeObserver<T, M> observer) {
        changeObservers.remove(observer);
    }

    protected int getCurrentTick() {
        return FMLCommonHandler.instance().getMinecraftServerInstance().getTickCounter();
    }

    protected void emitEvent(IIngredientComponentStorageObservable.StorageChangeEvent<T, M> event) {
        if (GeneralConfig.ingredientNetworkObserverEnableMultithreading) {
            // Make sure we are running on the main server thread to avoid concurrency exceptions
            FMLCommonHandler.instance().getMinecraftServerInstance().addScheduledTask(() -> {
                for (IIngredientComponentStorageObservable.IIndexChangeObserver<T, M> observer : getObserversCopy()) {
                    observer.onChange(event);
                }
            });
        } else {
            for (IIngredientComponentStorageObservable.IIndexChangeObserver<T, M> observer : getObserversCopy()) {
                observer.onChange(event);
            }
        }
    }

    protected synchronized List<IIngredientComponentStorageObservable.IIndexChangeObserver<T, M>> getObserversCopy() {
        return Lists.newArrayList(this.changeObservers);
    }

    protected void observe() {
        if (!this.changeObservers.isEmpty()) {
            if (GeneralConfig.ingredientNetworkObserverEnableMultithreading) {
                for (int channel : getNetwork().getChannels()) {
                    WORKER_POOL.execute(() -> observe(channel));
                }
            }
            for (int channel : getNetwork().getChannels()) {
                observe(channel);
            }
        }
    }

    protected synchronized Set<PrioritizedPartPos> getPositionsCopy(int channel) {
        return Sets.newHashSet(getNetwork().getPrioritizedPositions(channel));
    }

    protected void observe(int channel) {
        int currentTick = getCurrentTick();

        // Prepare ticking collections
        Map<PrioritizedPartPos, Integer> channelTargetTicks = observeTargetTicks.get(channel);
        if (channelTargetTicks == null) {
            channelTargetTicks = Maps.newHashMap();
        }
        Map<PrioritizedPartPos, Integer> channelIntervals = this.observeTargetTickIntervals.get(channel);
        if (channelIntervals == null) {
            channelIntervals = Maps.newHashMap();
        }

        // Calculate diff of all positions
        Map<PrioritizedPartPos, IngredientCollectionDiffManager<T, M>> diffManagers = this.channeledDiffManagers.get(channel);
        if (diffManagers == null) {
            diffManagers = Maps.newHashMap();
            this.channeledDiffManagers.put(channel, diffManagers);
        }

        // Check if we should diagnoze the observer
        boolean isBeingDiagnozed = NetworkDiagnostics.getInstance().isBeingDiagnozed();
        Map<PartPos, Long> lastSecondDurations = network.getLastSecondDurationIndex();
        if (!isBeingDiagnozed && !lastSecondDurations.isEmpty()) {
            // Make sure we aren't using any unnecessary memory.
            lastSecondDurations.clear();
        }

        // Emit diffs for all current positions
        Set<PrioritizedPartPos> positions = getPositionsCopy(channel);
        for (PrioritizedPartPos partPos : positions) {
            // Get current time if diagnostics are enabled
            long startTime = 0;
            if (isBeingDiagnozed) {
                startTime = System.nanoTime();
            }

            // Check if we should observe this position in this tick
            int lastTick = channelTargetTicks.getOrDefault(partPos, currentTick);
            if (lastTick <= currentTick) {
                // If an inventory state is exposed, check if it has changed since the last observation call.
                boolean skipPosition = false;
                IInventoryState inventoryState = TileHelpers.getCapability(partPos.getPartPos().getPos(),
                        partPos.getPartPos().getSide(), Capabilities.INVENTORY_STATE);
                if (inventoryState != null) {
                    Integer lastState = this.lastInventoryStates.get(partPos.getPartPos());
                    int newState = inventoryState.getHash();
                    if (lastState != null && lastState == newState) {
                        // Skip this position if it hasn't not changed
                        skipPosition = true;
                    } else {
                        this.lastInventoryStates.put(partPos.getPartPos(), newState);
                    }
                }

                if (!skipPosition) {
                    IngredientCollectionDiffManager<T, M> diffManager = diffManagers.get(partPos);
                    if (diffManager == null) {
                        diffManager = new IngredientCollectionDiffManager<>(network.getComponent());
                        diffManagers.put(partPos, diffManager);
                    }

                    // Emit event of diff
                    IngredientCollectionDiff<T, M> diff = diffManager.onChange(getNetwork().getRawInstances(partPos.getPartPos()));
                    boolean hasChanges = false;
                    if (diff.hasAdditions()) {
                        hasChanges = true;
                        this.emitEvent(new IIngredientComponentStorageObservable.StorageChangeEvent<>(channel, partPos,
                                IIngredientComponentStorageObservable.Change.ADDITION, false, diff.getAdditions()));
                    }
                    if (diff.hasDeletions()) {
                        hasChanges = true;
                        this.emitEvent(new IIngredientComponentStorageObservable.StorageChangeEvent<>(channel, partPos,
                                IIngredientComponentStorageObservable.Change.DELETION, diff.isCompletelyEmpty(), diff.getDeletions()));
                    }

                    // Update the next tick value
                    int tickInterval = channelIntervals.getOrDefault(partPos, GeneralConfig.ingredientNetworkObserverFrequencyMax);
                    // Decrease the frequency when changes were detected
                    // Increase the frequency when no changes were detected
                    // This will make it so that quickly changing storages will be observed
                    // more frequently than slowly changing storages
                    boolean tickIntervalChanged = false;
                    if (hasChanges) {
                        if (tickInterval > GeneralConfig.ingredientNetworkObserverFrequencyMin) {
                            tickIntervalChanged = true;
                            tickInterval = Math.max(GeneralConfig.ingredientNetworkObserverFrequencyMin, tickInterval - GeneralConfig.ingredientNetworkObserverFrequencyDecreaseFactor);
                        }
                    } else {
                        if (tickInterval < GeneralConfig.ingredientNetworkObserverFrequencyMax) {
                            tickIntervalChanged = true;
                            tickInterval = Math.min(GeneralConfig.ingredientNetworkObserverFrequencyMax, tickInterval + GeneralConfig.ingredientNetworkObserverFrequencyIncreaseFactor);
                        }
                    }
                    // No need to store the interval if it == 1, as the previous or default value will
                    // definitely also cause this part to tick in next tick.
                    // This makes these cases slightly faster, as no map updates are needed.
                    if (tickInterval != 1) {
                        channelTargetTicks.put(partPos, currentTick + tickInterval);

                    }
                    // Only update when the interval has changed.
                    // In most cases, this will remain the same.
                    if (tickIntervalChanged) {
                        if (tickInterval != GeneralConfig.ingredientNetworkObserverFrequencyMax) {
                            channelIntervals.put(partPos, tickInterval);
                        } else {
                            channelIntervals.remove(partPos);
                        }
                    }
                }
            }

            // Calculate duration if diagnostics are enabled
            if (isBeingDiagnozed) {
                long duration = System.nanoTime() - startTime;
                PartPos interfacePos = PartTarget.fromCenter(partPos.getPartPos()).getTarget();
                Long lastDuration = lastSecondDurations.get(interfacePos);
                if (lastDuration != null) {
                    duration = duration + lastDuration;
                }
                lastSecondDurations.put(interfacePos, duration);
            }
        }

        // Emit deletions for all removed positions
        List<PrioritizedPartPos> lastRemovedPositions = this.lastRemoved.get(channel);
        if (lastRemovedPositions != null) {
            for (PrioritizedPartPos partPos : lastRemovedPositions) {
                IngredientCollectionDiffManager<T, M> diffManager = diffManagers.get(partPos);
                if (diffManager != null) {
                    // Emit event of diff with *empty* iterator
                    IngredientCollectionDiff<T, M> diff = diffManager.onChange(Iterators.forArray());
                    // No additions are possible
                    if (diff.hasDeletions()) {
                        this.emitEvent(new IIngredientComponentStorageObservable.StorageChangeEvent<>(channel, partPos,
                                IIngredientComponentStorageObservable.Change.DELETION, diff.isCompletelyEmpty(), diff.getDeletions()));
                    }
                }
            }
            this.lastRemoved.remove(channel);
        }

        // Store our new ticking collections
        if (!channelTargetTicks.isEmpty()) {
            observeTargetTicks.put(channel, channelTargetTicks);
        }
        if (!channelIntervals.isEmpty()) {
            observeTargetTickIntervals.put(channel, channelIntervals);
        }
    }

}
