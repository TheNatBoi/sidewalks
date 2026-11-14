package org.openstreetmap.josm.plugins.mapwithai.street_level.actions;

import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * Installs a one-shot click handler: the next left-click on a highway way copies its name=*
 * into is_sidepath:of:name=* for the provided target ways, then removes itself.
 */
public final class PickRoadClickHandler extends MouseAdapter {
    public static final String KEY = "is_sidepath:of:name";

    private final List<Way> targets;
	private final Collection<Way> selectionToRestore = new HashSet<>();
	
    public PickRoadClickHandler(List<Way> targets) {
        this.targets = targets;
    }

    public void install() {
        final MapFrame map = MainApplication.getMap();
        if (map == null || map.mapView == null) return;
		final OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
		if (layer != null && layer.getDataSet() != null) {
			selectionToRestore.clear();
			selectionToRestore.addAll(layer.getDataSet().getSelectedWays());
		}
        map.mapView.addMouseListener(this);
        new Notification("Click a road (highway=*) to set " + KEY + " on the selected way(s).")
                .setDuration(6000)
                .show();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;

        final MapFrame map = MainApplication.getMap();
        if (map == null) {
            cleanup(null);
            return;
        }

        final MapView mv = map.mapView;
        if (mv == null) {
            cleanup(null);
            return;
        }

        final Point p = e.getPoint();

        // Your JOSM build's signature is: getNearestWay(Point, Predicate)
        final Way road = mv.getNearestWay(p, OsmPrimitive::isUsable);

        if (road == null || !road.hasKey("highway")) {
            new Notification("No highway way found under cursor. Click directly on a road.")
                    .setDuration(3500)
                    .show();
            return;
        }

		final String highway = road.get("highway");   // classification value (e.g., residential, primary)
		final String roadName = road.get("name");     // may be null/blank
		final String roadRef = road.get("ref");       // may be null/blank

		final List<Command> commands = new ArrayList<>();
		commands.add(new ChangePropertyCommand(this.targets, "is_sidepath", "yes"));

		if (highway != null && !highway.isBlank()) {
			commands.add(new ChangePropertyCommand(this.targets, "is_sidepath:of", highway));
		}

		if (roadName != null && !roadName.isBlank()) {
			commands.add(new ChangePropertyCommand(this.targets, "is_sidepath:of:name", roadName));
		}

		if (roadRef != null && !roadRef.isBlank()) {
			commands.add(new ChangePropertyCommand(this.targets, "is_sidepath:of:ref", roadRef));
		}
		
        if (roadName == null || roadName.isBlank()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    "The clicked road has no name=* tag.",
                    "No road name",
                    JOptionPane.WARNING_MESSAGE
            );
            UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded("Set sidepath tags", commands));
            restoreSelection();
            cleanup(mv);
            return;
        }

        commands.add(new ChangePropertyCommand(this.targets, KEY, roadName));
        new Notification("Set " + KEY + "=" + roadName + " on " + this.targets.size() + " way(s).")
                .setDuration(2000)
                .show();
        UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded("Set sidepath tags", commands));
        restoreSelection();
        cleanup(mv);
    }

    private void restoreSelection() {
        final OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
        if (layer != null && layer.getDataSet() != null) {
            layer.getDataSet().setSelected(selectionToRestore);
        }
    }

    private void cleanup(MapView mv) {
        if (mv != null) {
            mv.removeMouseListener(this);
        } else {
            final MapFrame map = MainApplication.getMap();
            if (map != null && map.mapView != null) {
                map.mapView.removeMouseListener(this);
            }
        }
    }
}