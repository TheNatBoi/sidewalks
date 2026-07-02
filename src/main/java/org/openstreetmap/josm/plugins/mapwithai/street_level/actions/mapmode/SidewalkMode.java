// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.street_level.actions.mapmode;

import static java.util.function.Predicate.not;
import static org.openstreetmap.josm.tools.I18n.tr;

import javax.swing.JOptionPane;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openstreetmap.josm.actions.mapmode.DrawAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.AddPrimitivesCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.data.osm.OsmUtils;
import org.openstreetmap.josm.data.osm.Tag;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * An action to make sidewalk mapping easier
 */
public class SidewalkMode extends MapMode implements MapFrame.MapModeChangeListener {
    /**
     * Make protected methods available for this class
     */
    private static final class DrawActionCustom extends DrawAction {
        private final Cursor cursorCopy = this.cursor;

        @Override
        public void updateStatusLine() {
            super.updateStatusLine();
        }

        @Override
        public void updateEnabledState() {
            super.updateEnabledState();
        }

        @Override
        public void readPreferences() {
            super.readPreferences();
        }

        @Override
        public void updateKeyModifiers(InputEvent e) {
            super.updateKeyModifiers(e);
        }

        @Override
        public void updateKeyModifiers(MouseEvent e) {
            super.updateKeyModifiers(e);
        }

        @Override
        public void updateKeyModifiers(ActionEvent e) {
            super.updateKeyModifiers(e);
        }

        @Override
        public void updateKeyModifiersEx(int modifiers) {
            super.updateKeyModifiersEx(modifiers);
        }

        @Override
        public void requestFocusInMapView() {
            super.requestFocusInMapView();
        }

        @Override
        public boolean isEditableDataLayer(Layer l) {
            return super.isEditableDataLayer(l);
        }
    }

    private static final String CROSSING = "crossing";
    private static final String FOOTWAY = "footway";
    private static final String STEPS = "steps";
    private static final String HIGHWAY = "highway";
	private static final String RAILWAY = "railway";
    private static final String SIDEWALK = "sidewalk";
    private static final String SURFACE = "surface";
    private final DrawActionCustom drawAction = new DrawActionCustom();
    private boolean entered;

    /**
     * Create a new sidewalk action
     */
    public SidewalkMode() {
        super(tr("Sidewalk mode"), "presets/transport/way/way_pedestrian.svg", tr("Draw sidewalks more efficiently"),
                Shortcut.registerShortcut("sidewalk:sidewalk", tr("Sidewalk mode"), KeyEvent.CHAR_UNDEFINED,
                        Shortcut.NONE),
                ImageProvider.getCursor("crosshair", SIDEWALK));
        new ImageProvider("presets/transport/way/way_pedestrian.svg").setOptional(true).getResource()
                .attachImageIcon(this);
        MapFrame.addMapModeChangeListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        super.actionPerformed(e);
        this.entered = MainApplication.getMap().mapMode == this;
    }

    @Override
    public void enterMode() {
        this.drawAction.enterMode();
        super.enterMode();

        MapFrame map = MainApplication.getMap();
        map.mapView.addMouseListener(this);
        map.mapView.addMouseMotionListener(this);
        new Notification(tr("How to exit {0}:<br/>Enter {1} mode twice (shortcut {2}).", this.getValue(NAME),
                this.drawAction.getValue(NAME), this.drawAction.getShortcut().toString()))
                        .setIcon(JOptionPane.INFORMATION_MESSAGE).show();
    }

    @Override
    public void exitMode() {
        this.drawAction.exitMode();
        super.exitMode();

        MapFrame map = MainApplication.getMap();
        map.mapView.removeMouseListener(this);
        map.mapView.removeMouseMotionListener(this);
    }

    @Override
    protected void updateStatusLine() {
        this.drawAction.updateStatusLine();
    }

    @Override
    public String getModeHelpText() {
        return this.drawAction.getModeHelpText();
    }

    @Override
    protected void readPreferences() {
        this.drawAction.readPreferences();
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return this.drawAction.layerIsSupported(l);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        super.mouseMoved(e);
        final var currentCursor = MainApplication.getMap().mapView.getCursor();
        if (currentCursor.equals(this.drawAction.cursorCopy)) {
            MainApplication.getMap().mapView.setNewCursor(this.cursor, this);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        this.updateKeyModifiers(e);
        if (this.ctrl) {
            return;
        }
        
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        final var ds = MainApplication.getLayerManager().getActiveDataSet();
        if (ds == null) return;

        final var undoRedoHandler = UndoRedoHandler.getInstance();
        if (undoRedoHandler == null || undoRedoHandler.getLastCommand() == null) {
            return;
        }

        final Collection<Way> selectedWays = ds.getSelectedWays();
        if (selectedWays.size() != 1) return;
        final Way way = selectedWays.iterator().next();

        if (way == null || way.firstNode() == null || way.getNodesCount() < 2) {
            return;
        }

        Node addedNode = way.lastNode();
        Command lastCmd = undoRedoHandler.getLastCommand();
        Iterable<?> cmdsToSearch = Collections.singletonList(lastCmd);
        
        if (lastCmd instanceof SequenceCommand seq && seq.getChildren() != null) {
            cmdsToSearch = seq.getChildren();
        }
        
        for (Object cmd : cmdsToSearch) {
            if (cmd instanceof AddCommand addCmd) {
                for (var prim : addCmd.getParticipatingPrimitives()) {
                    if (prim instanceof Node n) {
                        addedNode = n;
                    }
                }
            }
        }

        // Add path keys to a *new* way
        if (way.getNodesCount() == 2 && !way.hasKeys() && (!undoRedoHandler.hasRedoCommands()
                || !(undoRedoHandler.getRedoCommands().get(0)instanceof ChangePropertyCommand changePropertyCommand
                        && changePropertyCommand.getParticipatingPrimitives().contains(way)))) {
            
            final var startNode = way.firstNode();
            final var startParents = startNode.getParentWays().stream().filter(w -> !w.equals(way) && isPath(w)).toList();
            
            Map<String, String> initialTags = Map.of(HIGHWAY, FOOTWAY, FOOTWAY, SIDEWALK);
            if (!startParents.isEmpty()) {
                Way p = startParents.get(0);
                if ("cycleway".equals(p.get(HIGHWAY))) {
                    initialTags = new TreeMap<>();
                    initialTags.put(HIGHWAY, "cycleway");
                    if (p.hasKey("cycleway")) initialTags.put("cycleway", p.get("cycleway"));
                } else if ("path".equals(p.get(HIGHWAY))) {
                    initialTags = new TreeMap<>();
                    initialTags.put(HIGHWAY, "path");
                    if (p.hasKey("footway")) initialTags.put("footway", p.get("footway"));
                    if (p.hasKey("bicycle")) initialTags.put("bicycle", p.get("bicycle"));
                }
            }
            undoRedoHandler.add(new ChangePropertyCommand(Collections.singleton(way), initialTags));
        }
        
        final var forwardDirection = way.lastNode().equals(addedNode);
        final var segmentStart = forwardDirection ? way.getNodesCount() - 2 : 0;

        final var parentWays = (forwardDirection ? way.firstNode() : way.lastNode()).getParentWays().stream()
                .filter(not(way::equals)).filter(SidewalkMode::isPath).toList();
                
        boolean isEndOfCrossing = way.getNode(forwardDirection ? segmentStart : 1).hasTag("barrier", "kerb")
                || (way.hasTag(FOOTWAY, CROSSING) && way.hasTag("crossing:continuous", "yes"))
                || (way.hasTag("cycleway", CROSSING) && way.hasTag("crossing:continuous", "yes"))
                || (way.hasTag("path", CROSSING) && way.hasTag("crossing:continuous", "yes"));

        if (isPath(way) && way.getNodesCount() >= 3 && isEndOfCrossing) {
            switchToPath(way, parentWays, forwardDirection);
        } else if (isPath(way) || !parentWays.isEmpty()) {
            final var segment = new WaySegment(way, segmentStart);
            final var crossingWay = segment.toWay();
            final var possibleWays = new ArrayList<>(Optional.ofNullable(way.getDataSet())
                    .orElse(OsmDataManager.getInstance().getEditDataSet()).searchWays(crossingWay.getBBox()));
            possibleWays.remove(way);
            possibleWays.removeIf(w -> !w.hasKey(HIGHWAY) && !w.hasKey(RAILWAY));
            final var sidewalkLayer = OsmUtils.getLayer(way);
			final var crossedWays = new ArrayList<Way>();
            for (var possibleCrossing : possibleWays) {
                if (Objects.equals(OsmUtils.getLayer(possibleCrossing), sidewalkLayer)
                        && Geometry.getDistanceWayWay(possibleCrossing, crossingWay) == 0
                        && !possibleCrossing.containsNode(crossingWay.firstNode())
                        && !possibleCrossing.containsNode(crossingWay.lastNode())) {
						crossedWays.add(possibleCrossing);
                }
            }
			if (!crossedWays.isEmpty()) {
			    boolean needsCrossingSegment = crossedWays.stream().anyMatch(SidewalkMode::isVehicularOrRailway);
			    
			    if (needsCrossingSegment) {
    				if (crossingWay.getLength() < Config.getPref().getInt("sidewalk.crossing.maxlength", 30)) {
    					createCrossingWayAcrossWays(way, crossingWay, crossedWays, parentWays, forwardDirection);
    				} else {
    					final var commands = new ArrayList<Command>(3);
    					for (var crossed : crossedWays) {
    						createCrossingNodes(way, crossed, commands);
    					}
    					if (!commands.isEmpty()) {
    						undoRedoHandler.add(SequenceCommand.wrapIfNeeded(tr("Create crossing nodes"), commands));
    					}
    				}
			    } else {
			        // Sidewalk crosses a sidewalk! No crossing segment needed, just make an intersection.
					final var commands = new ArrayList<Command>(3);
					for (var crossed : crossedWays) {
						createCrossingNodes(way, crossed, commands);
					}
					if (!commands.isEmpty()) {
						undoRedoHandler.add(SequenceCommand.wrapIfNeeded(tr("Create intersection nodes"), commands));
					}
			    }
			}
        }
    }

    private static void switchToPath(Way way, Collection<Way> parentWays, boolean forwardDirection) {
        final var newParentWays = new ArrayList<>((forwardDirection ? way.lastNode() : way.firstNode()).getParentWays()
                .stream().filter(not(way::equals)).filter(SidewalkMode::isPath).toList());
        final var actualWays = newParentWays.isEmpty() ? parentWays : newParentWays;
        final var commands = new ArrayList<Command>(3);
        final var startIndex = forwardDirection ? 0 : 1;
        commands.add(new ChangeNodesCommand(way,
                new ArrayList<>(way.getNodes().subList(startIndex, way.getNodesCount() - 1 + startIndex))));
        final var stubWay = new Way();
        if (forwardDirection) {
            stubWay.addNode(way.getNode(way.getNodesCount() - 2));
            stubWay.addNode(way.lastNode());
        } else {
            stubWay.addNode(way.firstNode());
            stubWay.addNode(way.getNode(1));
        }
        stubWay.putAll(TagCollection.commonToAllPrimitives(actualWays).asList().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        commands.add(new AddPrimitivesCommand(Collections.singletonList(stubWay.save()), way.getDataSet()));
        UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded(tr("Add path"), commands));
    }

	private static boolean isPath(Way w) {
	    return w.hasTag(HIGHWAY, FOOTWAY) || w.hasTag(HIGHWAY, "cycleway") || w.hasTag(HIGHWAY, "path");
	}

	private static boolean isVehicularOrRailway(Way w) {
	    if (w.hasKey(RAILWAY)) return true;
	    if (!w.hasKey(HIGHWAY)) return false;
	    // Pedestrian/bike paths are NOT vehicular roads, so they don't trigger "crossing segments"
	    if (w.hasTag(HIGHWAY, "footway", "path", "steps", "pedestrian", "cycleway")) return false;
	    return true;
	}

	private static boolean isContinuousCrossing(Way possibleCrossing) {
		return possibleCrossing.hasTag(HIGHWAY, "service")
						&& possibleCrossing.hasTag("service", "alley", "drive-through", "driveway", "emergency_access");
	}

	private void createCrossingWayAcrossWays(Way way, Way crossingWay, Collection<Way> crossedWays,
			Collection<Way> parentWays, boolean forwardDirection) {

		final var undoRedoHandler = UndoRedoHandler.getInstance();
		final var newNodes = new ArrayList<>(way.getNodes());
		final var commands = new ArrayList<Command>(8);

		final boolean isContinuous = crossedWays.stream().allMatch(SidewalkMode::isContinuousCrossing);

        // Inherit path type
		String hwType = way.hasTag(HIGHWAY) ? way.get(HIGHWAY) : FOOTWAY;
		if (!way.hasTag(HIGHWAY) && !parentWays.isEmpty()) {
			hwType = parentWays.iterator().next().get(HIGHWAY);
		}
		
		crossingWay.put(HIGHWAY, hwType);
		if ("cycleway".equals(hwType)) {
			crossingWay.put("cycleway", CROSSING);
		} else if ("path".equals(hwType)) {
			crossingWay.put("path", CROSSING);
		} else {
			crossingWay.put(HIGHWAY, FOOTWAY);
			crossingWay.put(FOOTWAY, CROSSING);
		}

		crossingWay.put("crossing:continuous", isContinuous ? "yes" : "no");

		if ("unmarked".equals(crossingWay.get("crossing"))) {
			crossingWay.put("crossing:markings", "no");
		}

		crossedWays.stream().filter(w -> w.hasTag(SURFACE)).findFirst().ifPresent(w -> crossingWay.put(SURFACE, w.get(SURFACE)));
		if (!crossingWay.hasTag(SURFACE) && way.hasTag(SURFACE)) {
			crossingWay.put(SURFACE, way.get(SURFACE));
		}

		if (crossedWays.stream().anyMatch(w -> Geometry.getDistanceWayWay(way, w) == 0)) {
			newNodes.remove(forwardDirection ? newNodes.size() - 1 : 0);
		}

		if (newNodes.isEmpty() || newNodes.size() == 1) {
			commands.add(DeleteCommand.delete(Collections.singleton(way), false, true));
		} else {
			commands.add(new ChangeNodesCommand(way, newNodes));
		}

		addKerbTagging(way, crossingWay, commands, !isContinuous);

		for (Way crossed : crossedWays) {
			final var nodes = createCrossingNodes(crossingWay, crossed, commands);
			String crossingKey = crossed.hasKey(RAILWAY) ? RAILWAY : HIGHWAY;
			
			for (Node n : nodes) {
				boolean wayUnmarked = "unmarked".equals(crossingWay.get("crossing"));
				boolean nodeUnmarked = "unmarked".equals(n.get("crossing"));
				
				Map<String, String> nodeTags = new TreeMap<>();
				nodeTags.put(crossingKey, CROSSING);
				
				if (wayUnmarked || nodeUnmarked) {
					crossingWay.put("crossing", "unmarked");
					crossingWay.put("crossing:markings", "no");
					nodeTags.put("crossing", "unmarked");
					nodeTags.put("crossing:markings", "no");
				} else if (crossingWay.hasKey("crossing")) {
					nodeTags.put("crossing", crossingWay.get("crossing"));
				} else if (n.hasKey("crossing")) {
					crossingWay.put("crossing", n.get("crossing"));
				}

				if (n.getDataSet() == null) {
					for (var entry : nodeTags.entrySet()) {
						n.put(entry.getKey(), entry.getValue());
					}
				} else {
					Map<String, String> toChange = new TreeMap<>();
					for (var entry : nodeTags.entrySet()) {
						if (!entry.getValue().equals(n.get(entry.getKey()))) {
							toChange.put(entry.getKey(), entry.getValue());
						}
					}
					if (!toChange.isEmpty()) {
						commands.add(new ChangePropertyCommand(Collections.singleton(n), toChange));
					}
				}
			}
		}
		
		commands.add(new AddPrimitivesCommand(Collections.singletonList(crossingWay.save()), way.getDataSet()));
		undoRedoHandler.add(SequenceCommand.wrapIfNeeded(tr("Create crossing way"), commands));

		if (!way.hasKeys() && !parentWays.isEmpty()) {
			undoRedoHandler.add(new ChangePropertyCommand(Collections.singletonList(way),
					TagCollection.commonToAllPrimitives(parentWays).asList().stream()
							.collect(Collectors.toMap(Tag::getKey, Tag::getValue))));
		}

		way.getDataSet().setSelected(forwardDirection ? crossingWay.lastNode() : crossingWay.firstNode());
		this.drawAction.updateKeyModifiers(new MouseEvent(MainApplication.getMap(),
				Long.hashCode(System.currentTimeMillis()), System.currentTimeMillis(),
				InputEvent.ALT_DOWN_MASK | (this.ctrl ? InputEvent.CTRL_DOWN_MASK : 0)
						| (this.shift ? InputEvent.SHIFT_DOWN_MASK : 0) | (this.meta ? InputEvent.META_DOWN_MASK : 0),
				0, 0, 0, false));
	}

    private static void addKerbTagging(Way originalWay, Way crossingWay, Collection<Command> usuallyRightCommands,
            boolean isCrossing) {
		if (isCrossing && Config.getPref().getBoolean("sidewalk.crossing.kerb", true)) {
            final var tagMap = Config.getPref().getListOfMaps("sidewalk.crossing.kerb.tags").stream().map(Map::entrySet)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (old, n) -> n, TreeMap::new));
            tagMap.putIfAbsent("barrier", "kerb");
            final var changingNodes = Stream.of(crossingWay.firstNode(), crossingWay.lastNode())
                    .filter(Objects::nonNull).filter(node -> !inMiddleOfPath(originalWay, crossingWay, node))
                    .toList();
            if (!changingNodes.isEmpty()) {
                usuallyRightCommands.add(new ChangePropertyCommand(changingNodes, tagMap));
            }
        }
    }

    private static boolean inMiddleOfPath(Way originalWay, Way crossingWay, Node node) {
        final var footways = node.getParentWays().stream().filter(not(crossingWay::equals))
                .filter(not(originalWay::equals)).filter(SidewalkMode::isPath).toList();
        if (footways.isEmpty()) {
            return false;
        } else if (footways.size() >= 2) {
            return true;
        }
        final var footway = footways.get(0);
        return footway.isClosed() || footway.isInnerNode(node);
    }

	private static Set<Node> createCrossingNodes(Way crossingWay, Way possibleCrossing, Collection<Command> commands) {
		final var dummyCommands = new ArrayList<Command>();
		final var intersection = Geometry.addIntersections(Arrays.asList(possibleCrossing, crossingWay), false, dummyCommands);

		if (intersection.isEmpty()) {
			return Collections.emptySet();
		}

		final var maxCrossingDistance = Config.getPref().getDouble("sidewalk.crossing.node.maxdistance", 6);
		final var dupeNodeDistance = Config.getPref().getDouble("sidewalk.crossing.node.dupedistance", 1);

		final var chosenNodes = new ArrayList<Node>(intersection.size());
		final var ds = crossingWay.getDataSet() != null ? crossingWay.getDataSet() : possibleCrossing.getDataSet();

		for (var rawNode : intersection) {
			Node node = rawNode;
			final var crossingSegment = Geometry.getClosestWaySegment(possibleCrossing, node);

			final var closestCrossing = Stream.of(crossingSegment.getFirstNode(), crossingSegment.getSecondNode())
					.filter(n -> (n.hasTag(HIGHWAY, CROSSING) || n.hasTag(RAILWAY, CROSSING)) && n.getParentWays().size() == 1)
					.min(Comparator.comparingDouble(node::distanceSq));
					
			if (closestCrossing.isPresent() && node.greatCircleDistance(closestCrossing.get()) < maxCrossingDistance) {
				node = closestCrossing.get();
			} else {
				final var closestNode = Stream.of(crossingSegment.getFirstNode(), crossingSegment.getSecondNode())
						.filter(n -> n.getParentWays().size() == 1 && !n.isTagged())
						.min(Comparator.comparingDouble(node::distanceSq)).orElse(null);
				if (closestNode != null && node.greatCircleDistance(closestNode) < dupeNodeDistance) {
					node = closestNode;
				}
			}
			chosenNodes.add(node);

			if (node.equals(rawNode) && ds != null && node.getDataSet() == null) {
				final Node finalNode = node;
				boolean alreadyAdded = commands.stream()
					.anyMatch(c -> c instanceof AddCommand ac && ac.getParticipatingPrimitives().contains(finalNode));
				if (!alreadyAdded) {
					commands.add(new AddCommand(ds, finalNode));
				}
			}
		}

		final var roadNodes = new ArrayList<>(possibleCrossing.getNodes());
		boolean roadChanged = false;
		for (var node : chosenNodes) {
			if (!roadNodes.contains(node)) {
				final var seg = Geometry.getClosestWaySegment(possibleCrossing, node);
				int insertIdx = roadNodes.indexOf(seg.getSecondNode());
				if (insertIdx != -1) {
					roadNodes.add(insertIdx, node);
					roadChanged = true;
				}
			}
		}
		if (roadChanged) {
			commands.add(new ChangeNodesCommand(possibleCrossing, roadNodes));
		}

		chosenNodes.sort(Comparator.comparingInt(n -> Geometry.getClosestWaySegment(crossingWay, n).getUpperIndex()));

		if (crossingWay.getDataSet() == null) {
			for (var node : chosenNodes) {
				if (!crossingWay.containsNode(node)) {
					crossingWay.addNode(Geometry.getClosestWaySegment(crossingWay, node).getUpperIndex(), node);
				}
			}
		} else {
			final var cwNodes = new ArrayList<>(crossingWay.getNodes());
			boolean cwChanged = false;
			for (var node : chosenNodes) {
				if (!cwNodes.contains(node)) {
					final var seg = Geometry.getClosestWaySegment(crossingWay, node);
					int safeIdx = cwNodes.indexOf(seg.getSecondNode());
					if (safeIdx != -1) {
						cwNodes.add(safeIdx, node);
						cwChanged = true;
					} else {
						cwNodes.add(seg.getUpperIndex(), node);
						cwChanged = true;
					}
				}
			}
			if (cwChanged) {
				commands.add(new ChangeNodesCommand(crossingWay, cwNodes));
			}
		}

		return Set.copyOf(chosenNodes);
	}

    @Override
    public void preferenceChanged(PreferenceChangeEvent e) {
        super.preferenceChanged(e);
        this.drawAction.preferenceChanged(e);
    }

    @Override
    protected boolean isEditableDataLayer(Layer l) {
        return this.drawAction.isEditableDataLayer(l);
    }

    @Override
    public void mapModeChange(MapMode oldMapMode, MapMode newMapMode) {
        if (oldMapMode == this && newMapMode instanceof DrawAction) {
            this.entered = false;
        } else if (this.entered && newMapMode instanceof DrawAction) {
            this.actionPerformed(null);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        MapFrame.removeMapModeChangeListener(this);
    }
}