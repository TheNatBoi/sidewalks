// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.street_level.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Shortcut;

public class TagRoadSidewalkSidesFromSelectedSidewalksAction extends JosmAction {
    public TagRoadSidewalkSidesFromSelectedSidewalksAction() {
        super(
            tr("Tag road sidewalk sides from selected sidewalks"),
            (String) null,
            tr("Select sidewalk ways, run this action, then click the road to split+tag sidewalk:left/right/both."),
            Shortcut.registerShortcut(
                "sidewalks:tag_road_sidewalk_sides",
                tr("Sidewalks: Tag road sidewalk sides from selected sidewalks"),
                KeyEvent.VK_C,
                Shortcut.ALT
            ),
            true
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final DataSet ds = MainApplication.getLayerManager().getActiveDataSet();
        if (ds == null) return;

        final List<Way> selectedWays = new ArrayList<>(ds.getSelectedWays());
        if (selectedWays.isEmpty()) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Select one or more sidewalk ways first."),
                tr("Nothing selected"),
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        new TagRoadSidewalkSidesClickHandler(selectedWays).install();
    }
}