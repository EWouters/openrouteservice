/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.fastisochrones.partitioning;

import com.graphhopper.storage.*;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.Helper;
import org.heigit.ors.fastisochrones.partitioning.storage.CellStorage;
import org.heigit.ors.fastisochrones.partitioning.storage.IsochroneNodeStorage;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.EdgeFilterSequence;
import org.heigit.ors.routing.graphhopper.extensions.util.ORSParameters.FastIsochrone;

import java.util.*;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.graphhopper.util.Helper.toLowerCase;
import static org.heigit.ors.fastisochrones.partitioning.FastIsochroneParameters.*;

/**
 * Factory for Fast Isochrone Preparation
 * <p>
 * This code is based on that from GraphHopper GmbH.
 *
 * @author Peter Karich
 * @author Hendrik Leuschner
 */
public class FastIsochroneFactory {
    private final List<PreparePartition> preparations = new ArrayList<>();
    private final Set<String> fastisochroneProfileStrings = new LinkedHashSet<>();
    private boolean disablingAllowed = true;
    private boolean enabled = false;
    private int preparationThreads;
    private ExecutorService threadPool;
    private IsochroneNodeStorage isochroneNodeStorage;
    private CellStorage cellStorage;

    public FastIsochroneFactory() {
        setPreparationThreads(1);
    }

    public void init(CmdArgs args) {
        setPreparationThreads(args.getInt(FastIsochrone.PREPARE + "threads", getPreparationThreads()));
        setMaxThreadCount(args.getInt(FastIsochrone.PREPARE + "threads", getMaxThreadCount()));
        setMaxCellNodesNumber(args.getInt(FastIsochrone.PREPARE + "maxcellnodes", getMaxCellNodesNumber()));
        String weightingsStr = args.get(FastIsochrone.PREPARE + "weightings", "");

        if ("no".equals(weightingsStr)) {
            // default is fastest and we need to clear this explicitely
            fastisochroneProfileStrings.clear();
        } else if (!weightingsStr.isEmpty()) {
            setFastIsochroneProfilesAsStrings(Arrays.asList(weightingsStr.split(",")));
        }

        boolean enableThis = !fastisochroneProfileStrings.isEmpty();
        setEnabled(enableThis);
        if (enableThis)
            setDisablingAllowed(args.getBool(FastIsochrone.INIT_DISABLING_ALLOWED, isDisablingAllowed()));
    }

    /**
     * @param profileStrings A list of multiple fast isochrone profile strings
     * @see #addFastIsochroneProfileAsString(String)
     */
    public FastIsochroneFactory setFastIsochroneProfilesAsStrings(List<String> profileStrings) {
        if (profileStrings.isEmpty())
            throw new IllegalArgumentException("It is not allowed to pass an empty list of CH profile strings");

        fastisochroneProfileStrings.clear();
        for (String profileString : profileStrings) {
            profileString = toLowerCase(profileString);
            profileString = profileString.trim();
            addFastIsochroneProfileAsString(profileString);
        }
        return this;
    }

    public Set<String> getFastisochroneProfileStrings() {
        return fastisochroneProfileStrings;
    }

    /**
     * Enables the use of fast isochrones to reduce isochrones query times. Disabled by default.
     *
     * @param profileString String representation of a weighting.
     */
    public FastIsochroneFactory addFastIsochroneProfileAsString(String profileString) {
        fastisochroneProfileStrings.add(profileString);
        return this;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables core calculation..
     */
    public final FastIsochroneFactory setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public final boolean isDisablingAllowed() {
        return disablingAllowed || !isEnabled();
    }

    /**
     * This method specifies if it is allowed to disable Core routing at runtime via routing hints.
     */
    public final FastIsochroneFactory setDisablingAllowed(boolean disablingAllowed) {
        this.disablingAllowed = disablingAllowed;
        return this;
    }

    public FastIsochroneFactory addPreparation(PreparePartition pp) {
        preparations.add(pp);
        return this;
    }

    public List<PreparePartition> getPreparations() {
        return preparations;
    }

    public int getPreparationThreads() {
        return preparationThreads;
    }


    /**
     * This method changes the number of threads used for preparation on import. Default is 1. Make
     * sure that you have enough memory when increasing this number!
     */
    public void setPreparationThreads(int preparationThreads) {
        this.preparationThreads = preparationThreads;
        this.threadPool = Executors.newFixedThreadPool(preparationThreads);
    }

    public void prepare(final StorableProperties properties) {
        ExecutorCompletionService completionService = new ExecutorCompletionService<>(threadPool);
        final String name = "PreparePartition";
        completionService.submit(new Runnable() {
            @Override
            public void run() {
                // toString is not taken into account so we need to cheat, see http://stackoverflow.com/q/6113746/194609 for other options
                Thread.currentThread().setName(name);
                getPreparations().get(0).prepare();
                setIsochroneNodeStorage(getPreparations().get(0).getIsochroneNodeStorage());
                setCellStorage(getPreparations().get(0).getCellStorage());
                properties.put(FastIsochrone.PREPARE + "date." + name, Helper.createFormatter().format(new Date()));
            }
        }, name);

        threadPool.shutdown();

        try {
            for (int i = 0; i < getPreparations().size(); i++) {
                completionService.take().get();
            }
        } catch (Exception e) {
            threadPool.shutdownNow();
            throw new RuntimeException(e);
        }
    }

    public void createPreparations(GraphHopperStorage ghStorage, EdgeFilterSequence edgeFilters) {
        if (!isEnabled() || !preparations.isEmpty())
            return;
        PreparePartition tmpPreparePartition = new PreparePartition(ghStorage, edgeFilters);
        addPreparation(tmpPreparePartition);
    }

    public void setExistingStorages() {
        setIsochroneNodeStorage(getPreparations().get(0).getIsochroneNodeStorage());
        setCellStorage(getPreparations().get(0).getCellStorage());
    }

    public IsochroneNodeStorage getIsochroneNodeStorage() {
        return isochroneNodeStorage;
    }

    public void setIsochroneNodeStorage(IsochroneNodeStorage isochroneNodeStorage) {
        this.isochroneNodeStorage = isochroneNodeStorage;
    }

    public CellStorage getCellStorage() {
        return cellStorage;
    }

    public void setCellStorage(CellStorage cellStorage) {
        this.cellStorage = cellStorage;
    }
}