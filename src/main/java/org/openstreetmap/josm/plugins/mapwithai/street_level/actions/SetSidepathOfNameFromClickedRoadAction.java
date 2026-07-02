package org.openstreetmap.josm.plugins.mapwithai.street_level.actions;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.tools.Shortcut;
import java.awt.event.KeyEvent;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

public class SetSidepathOfNameFromClickedRoadAction extends JosmAction {
    public SetSidepathOfNameFromClickedRoadAction() {
        // IMPORTANT: icon name can be null to avoid any icon lookup issues
		super(
			"Set is_sidepath:of: tags from road",
			null,
			"Select one or more sidepath ways, run this action, then click a road to copy its tags.",
			Shortcut.registerShortcut(
				"mapwithai:set_sidepath_of_from_clicked_road",
				"MapWithAI: Set is_sidepath:of:* from clicked road",
				KeyEvent.VK_S,
				Shortcut.SHIFT
			),
			false
		);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
        if (layer == null || layer.getDataSet() == null) return;

        final List<Way> selectedWays = new ArrayList<>(layer.getDataSet().getSelectedWays());
        if (selectedWays.isEmpty()) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                "Select one or more sidepath ways first.",
                "Nothing selected",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        new PickRoadClickHandler(selectedWays).install();
    }
}