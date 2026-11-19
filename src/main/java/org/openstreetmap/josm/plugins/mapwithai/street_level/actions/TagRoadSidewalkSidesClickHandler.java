// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.street_level.actions;

import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.Collections;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.tools.Geometry;

/**
 * One-shot tool:
 *  - user selects sidewalk ways
 *  - user clicks a road
 *  - plugin splits the road at sidewalk coverage boundaries and sets sidewalk:left/right/both tags
 *  - restores the original sidewalk selection
 *
 * Intersection rule:
 *  - do not propagate coverage "through" nodes that are shared with another highway=* way;
 *    they become hard boundaries.
 */
public final class TagRoadSidewalkSidesClickHandler extends MouseAdapter {
    private static final String HIGHWAY = "highway";
    private static final double WARN_DISTANCE_METERS = 15.0;

    private final List<Way> selectedSidewalks;
    private final Collection<Way> selectionToRestore = new HashSet<>();

    public TagRoadSidewalkSidesClickHandler(List<Way> selectedSidewalks) {
        this.selectedSidewalks = selectedSidewalks;
    }

    public void install() {
        final MapFrame map = MainApplication.getMap();
        if (map == null || map.mapView == null) return;

        final DataSet ds = MainApplication.getLayerManager().getActiveDataSet();
        if (ds != null) {
            selectionToRestore.clear();
            selectionToRestore.addAll(ds.getSelectedWays());
        }

        map.mapView.addMouseListener(this);
        new Notification("Click a road (highway=*) to split+tag sidewalk:left/right/both based on selected sidewalks.")
            .setDuration(7000)
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

        final DataSet ds = MainApplication.getLayerManager().getActiveDataSet();
        if (ds == null) {
            cleanup(mv);
            return;
        }

        final Point p = e.getPoint();
        final Way road = mv.getNearestWay(p, OsmPrimitive::isUsable);
        if (road == null || !road.hasKey(HIGHWAY)) {
            new Notification("No highway way found under cursor. Click directly on a road.")
                .setDuration(3500)
                .show();
            cleanup(mv);
            return;
        }

        // Compute coverage intervals for left/right in terms of distance-along-road (meters).
        // Intersections are not split points: the selected sidewalk endpoints define the
        // coverage boundaries.
        final List<Interval> left = new ArrayList<>();
        final List<Interval> right = new ArrayList<>();

        boolean warned = false;

        for (Way sw : selectedSidewalks) {
            if (sw == null || sw.getNodesCount() < 2) continue;

            // Determine side using midpoint of sidewalk and local road direction
            final Side side = determineSide(road, sw);
            if (side == null) continue;

            final Node a = sw.firstNode();
            final Node b = sw.lastNode();
            if (a == null || b == null) continue;

            final Projection pa = projectOntoRoad(road, a.getCoor());
            final Projection pb = projectOntoRoad(road, b.getCoor());
            if (pa == null || pb == null) continue;

            if (!warned && (pa.perpMeters > WARN_DISTANCE_METERS || pb.perpMeters > WARN_DISTANCE_METERS)) {
                warned = true;
                new Notification("Warning: a selected sidewalk endpoint is > 15m from the road; splitting/tagging may be inaccurate.")
                    .setDuration(8000)
                    .show();
            }

            Interval raw = new Interval(Math.min(pa.alongMeters, pb.alongMeters),
                    Math.max(pa.alongMeters, pb.alongMeters));

            if (side == Side.LEFT) left.add(raw);
            else right.add(raw);
        }

        // Bridge any gaps (like pedestrian crossings) between selected sidewalks on the same side
        final List<Interval> leftU = union(bridgeGaps(left));
        final List<Interval> rightU = union(bridgeGaps(right));

        // Find the absolute extents of the selected sidewalks
        double minCoverage = Double.MAX_VALUE;
        double maxCoverage = -Double.MAX_VALUE;
        for (Interval i : leftU) {
            minCoverage = Math.min(minCoverage, i.a);
            maxCoverage = Math.max(maxCoverage, i.b);
        }
        for (Interval i : rightU) {
            minCoverage = Math.min(minCoverage, i.a);
            maxCoverage = Math.max(maxCoverage, i.b);
        }
        
        if (minCoverage == Double.MAX_VALUE) {
            cleanup(mv); // No valid sidewalk projections found
            return;
        }

        // Determine split points from sidewalk coverage boundaries only.
        final var splitMeters = new HashSet<Double>();
        leftU.forEach(i -> { splitMeters.add(i.a); splitMeters.add(i.b); });
        rightU.forEach(i -> { splitMeters.add(i.a); splitMeters.add(i.b); });

        // Remove endpoints (0 and total length) so we don't try to split at ends
        final double totalLen = road.getLength(); // meters
        splitMeters.removeIf(x -> x <= 0.01 || x >= totalLen - 0.01);
		// Build split nodes by snapping to existing nodes OR creating new ones
		final List<Command> addNodesCommands = new ArrayList<>();
		final List<Node> splitNodes = new ArrayList<>();
		final List<Double> orderedSplits = splitMeters.stream().sorted().collect(Collectors.toList());

		List<Node> currentWayNodes = new ArrayList<>(road.getNodes());
		boolean nodesChanged = false;

		for (double s : orderedSplits) {
			Node n = findExistingNodeNearAlong(road, s, 2.0); // 2m tolerance
			if (n != null) {
				splitNodes.add(n);
			} else {
				LatLon ll = interpolateAlongRoad(road, s);
				if (ll != null) {
					Node newNode = new Node(ll);
					addNodesCommands.add(new org.openstreetmap.josm.command.AddCommand(ds, newNode));
					
					int segIdx = findSegmentIndexAtAlongMeters(currentWayNodes, s);
					if (segIdx >= 0 && segIdx < currentWayNodes.size() - 1) {
						currentWayNodes.add(segIdx + 1, newNode);
						nodesChanged = true;
						splitNodes.add(newNode);
					}
				}
			}
		}

		// 1. ADD NODES TO THE WAY FIRST
		if (nodesChanged) {
			addNodesCommands.add(new org.openstreetmap.josm.command.ChangeNodesCommand(road, currentWayNodes));
		}
		if (!addNodesCommands.isEmpty()) {
			UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded("Add split nodes to road", addNodesCommands));
		}

		final List<Node> uniqueSplitNodes = splitNodes.stream()
				.filter(Objects::nonNull)
				.distinct()
				.collect(Collectors.toList());

		// Save the full list of nodes BEFORE the split mutates the road object
		final List<Node> fullNodesBeforeSplit = new ArrayList<>(road.getNodes());

		// 2. NOW SPLIT THE WAY
		if (!uniqueSplitNodes.isEmpty()) {
			final SplitWayCommand splitCmd = SplitWayCommand.split(road, uniqueSplitNodes, Collections.emptyList());
			if (splitCmd != null) {
				UndoRedoHandler.getInstance().add(splitCmd);
			}
		}

		// 3. FIND THE RESULTING PIECES USING THE SAVED FULL NODE LIST
		final List<Way> roadPieces;
		if (!uniqueSplitNodes.isEmpty()) {
			// Find all ways in the dataset that are made entirely of the original road's nodes
			final HashSet<Node> originalNodesSet = new HashSet<>(fullNodesBeforeSplit);
			roadPieces = ds.getWays().stream()
				.filter(w -> w != null && w.hasKey("highway") && !w.getNodes().isEmpty())
				.filter(w -> originalNodesSet.containsAll(w.getNodes()))
				.collect(Collectors.toList());
		} else {
			roadPieces = List.of(road);
		}

		// ---- Phase 2: tag pieces ----
		final List<Command> tagCommands = new ArrayList<>();

		// The original 'road' object was truncated by the SplitWayCommand.
		// We create a temporary, unmanaged way to hold the full geometry 
		// so mapPieceAlongToOriginal can project distances correctly.
		Way originalGeometryRoad = new Way();
		originalGeometryRoad.setNodes(fullNodesBeforeSplit);

		for (Way piece : roadPieces) {
			if (piece == null || piece.getDataSet() == null) continue;

			final double mid = piece.getLength() / 2.0;
			final LatLon midLl = interpolateAlongRoad(piece, mid);
			final Projection pm = projectOntoRoad(piece, midLl);
			final double alongInPiece = (pm == null) ? mid : pm.alongMeters;

			// Calculate where this piece's midpoint sits on the ORIGINAL road geometry
			final double globalAlong = mapPieceAlongToOriginal(originalGeometryRoad, piece, alongInPiece);

			// If the piece is outside the bounding box of the selected sidewalks, skip it entirely
			if (globalAlong < minCoverage || globalAlong > maxCoverage) {
				continue;
			}

			final boolean hasL = contains(leftU, globalAlong);
			final boolean hasR = contains(rightU, globalAlong);

			final Map<String, String> tagsToSet = computeSidewalkTags(hasL, hasR);

			// Only set keys if absent
			final Map<String, String> actuallySet = new TreeMap<>();
			for (var ent : tagsToSet.entrySet()) {
				if (!piece.hasKey(ent.getKey())) {
					actuallySet.put(ent.getKey(), ent.getValue());
				}
			}

			if (!actuallySet.isEmpty()) {
				tagCommands.add(new ChangePropertyCommand(List.of(piece), actuallySet));
			}
		}

		if (!tagCommands.isEmpty()) {
			UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded("Tag sidewalk sides", tagCommands));
		}

        // Restore selection
        ds.setSelected(selectionToRestore);

        cleanup(mv);
    }

    private void cleanup(MapView mv) {
        if (mv != null) mv.removeMouseListener(this);
        else {
            final MapFrame map = MainApplication.getMap();
            if (map != null && map.mapView != null) map.mapView.removeMouseListener(this);
        }
    }

    // ---------- helpers ----------

    private enum Side { LEFT, RIGHT }

    private static final class Interval {
        final double a;
        final double b;
        Interval(double a, double b) { this.a = a; this.b = b; }
    }

    private static final class Projection {
        final double alongMeters;   // distance along road from start
        final double perpMeters;    // perpendicular distance to road
        Projection(double alongMeters, double perpMeters) {
            this.alongMeters = alongMeters;
            this.perpMeters = perpMeters;
        }
    }

    private static Side determineSide(Way road, Way sidewalk) {
        // Use sidewalk midpoint (node mid) and local road direction at nearest segment
        final Node mid = sidewalk.getNode(sidewalk.getNodesCount() / 2);
        if (mid == null || mid.getCoor() == null) return null;

        final WaySegment seg = Geometry.getClosestWaySegment(road, mid);
        if (seg == null) return null;

        final Node r1 = seg.getFirstNode();
        final Node r2 = seg.getSecondNode();
        if (r1 == null || r2 == null || r1.getCoor() == null || r2.getCoor() == null) return null;

        // Work in projected East/North meters around r1
        final var p = mid.getEastNorth();
        final var a = r1.getEastNorth();
        final var b = r2.getEastNorth();
        if (p == null || a == null || b == null) return null;

        final double dx = b.east() - a.east();
        final double dy = b.north() - a.north();
        final double px = p.east() - a.east();
        final double py = p.north() - a.north();

        final double cross = dx * py - dy * px;
        return cross > 0 ? Side.LEFT : Side.RIGHT;
    }

	private static Projection projectOntoRoad(Way road, LatLon ll) {
		if (road == null || ll == null) return null;
		// Build a temporary node for geometry routines
		final Node n = new Node(ll);

		// Use the actual road geometry here (do NOT reference mouseClicked locals)
		final WaySegment seg = Geometry.getClosestWaySegment(road, n);
		if (seg == null) return null;

		// Find closest point on that segment...
		final var p = n.getEastNorth();
		final var a = seg.getFirstNode().getEastNorth();
		final var b = seg.getSecondNode().getEastNorth();
		if (p == null || a == null || b == null) return null;

		final double dx = b.east() - a.east();
		final double dy = b.north() - a.north();
		final double px = p.east() - a.east();
		final double py = p.north() - a.north();
		final double denom = dx * dx + dy * dy;

		// fraction along segment [0..1]
		final double t = denom == 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / denom));

		final var cp = new org.openstreetmap.josm.data.coor.EastNorth(
			a.east() + t * dx,
			a.north() + t * dy
		);

        final double perp = cp.distance(p); // meters in EastNorth

        // Distance along road: sum segment lengths up to this segment + partial segment
        double along = 0.0;
        final List<Node> nodes = road.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            final Node n1 = nodes.get(i);
            final Node n2 = nodes.get(i + 1);
            if (n1 == null || n2 == null) continue;
            final double segLen = n1.greatCircleDistance(n2);
            if (Objects.equals(n1, seg.getFirstNode()) && Objects.equals(n2, seg.getSecondNode())) {
                along += segLen * t;
                break;
            }
            along += segLen;
        }

        return new Projection(along, perp);
    }

    private static LatLon interpolateAlongRoad(Way road, double alongMeters) {
        if (road == null || road.getNodesCount() < 2) return null;
        double remain = alongMeters;
        for (int i = 0; i < road.getNodesCount() - 1; i++) {
            Node a = road.getNode(i);
            Node b = road.getNode(i + 1);
            if (a == null || b == null) continue;
            double len = a.greatCircleDistance(b);
            if (len <= 0) continue;
            if (remain <= len) {
                double t = remain / len;
                LatLon la = a.getCoor();
                LatLon lb = b.getCoor();
                if (la == null || lb == null) return null;
                return new LatLon(
                    la.lat() + t * (lb.lat() - la.lat()),
                    la.lon() + t * (lb.lon() - la.lon())
                );
            }
            remain -= len;
        }
        return road.lastNode() != null ? road.lastNode().getCoor() : null;
    }

    private static Node findExistingNodeNearAlong(Way road, double alongMeters, double tolMeters) {
        double acc = 0.0;
        for (int i = 0; i < road.getNodesCount(); i++) {
            Node n = road.getNode(i);
            if (n == null) continue;
            if (i > 0) {
                Node prev = road.getNode(i - 1);
                if (prev != null) acc += prev.greatCircleDistance(n);
            }
            if (Math.abs(acc - alongMeters) <= tolMeters) return n;
        }
        return null;
    }

    private static List<Interval> union(List<Interval> intervals) {
        final List<Interval> sorted = intervals.stream()
            .filter(i -> i != null && i.b > i.a)
            .sorted(Comparator.comparingDouble(i -> i.a))
            .collect(Collectors.toList());

        final List<Interval> out = new ArrayList<>();
        Interval cur = null;
        for (Interval i : sorted) {
            if (cur == null) {
                cur = i;
            } else if (i.a <= cur.b + 0.01) { // merge with tiny tolerance
                cur = new Interval(cur.a, Math.max(cur.b, i.b));
            } else {
                out.add(cur);
                cur = i;
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private static boolean contains(List<Interval> u, double x) {
        for (Interval i : u) {
            if (x >= i.a && x <= i.b) return true;
        }
        return false;
    }

	private static Map<String, String> computeSidewalkTags(boolean hasLeft, boolean hasRight) {
		final Map<String, String> m = new TreeMap<>();
		
		if (hasLeft && hasRight) {
			// Both are 'separate' -> use only 'sidewalk:both'
			m.put("sidewalk:both", "separate");
		} else if (!hasLeft && !hasRight) {
			// Both are 'no' -> use only 'sidewalk:both'
			m.put("sidewalk:both", "no");
		} else if (hasLeft) {
			// Left is 'separate', right is 'no' -> use only left/right
			m.put("sidewalk:left", "separate");
			m.put("sidewalk:right", "no");
		} else if (hasRight) {
			// Right is 'separate', left is 'no' -> use only left/right
			m.put("sidewalk:left", "no");
			m.put("sidewalk:right", "separate");
		}
		
		return m;
	}
	
    private static double mapPieceAlongToOriginal(Way originalRoad, Way piece, double alongInPiece) {
        // Simplification: assume piece is in original order and uses original geometry
        // We'll approximate by projecting piece point onto original and returning that along.
        final LatLon ll = interpolateAlongRoad(piece, alongInPiece);
        if (ll == null) return alongInPiece;
        final Projection p = projectOntoRoad(originalRoad, ll);
        return p == null ? alongInPiece : p.alongMeters;
    }
	
	private static class NodeDistanceToRefNodeComparator implements Comparator<Node>, java.io.Serializable {
		private static final long serialVersionUID = 1L;

		private final org.openstreetmap.josm.data.coor.EastNorth refPoint;
		private final org.openstreetmap.josm.data.coor.EastNorth refPoint2;
		private final boolean projectToSegment;

		NodeDistanceToRefNodeComparator(Node referenceNode, Node referenceNode2, boolean projectFirst) {
			refPoint = referenceNode.getEastNorth();
			refPoint2 = referenceNode2.getEastNorth();
			projectToSegment = projectFirst;
		}

		@Override
		public int compare(Node first, Node second) {
			var firstPosition = first.getEastNorth();
			var secondPosition = second.getEastNorth();

			if (projectToSegment) {
				firstPosition = Geometry.closestPointToSegment(refPoint, refPoint2, firstPosition);
				secondPosition = Geometry.closestPointToSegment(refPoint, refPoint2, secondPosition);
			}

			double distanceFirst = firstPosition.distance(refPoint);
			double distanceSecond = secondPosition.distance(refPoint);
			return Double.compare(distanceFirst, distanceSecond);
		}
	}

	private static int findSegmentIndexAtAlongMeters(List<Node> nodes, double alongMeters) {
		if (nodes == null || nodes.size() < 2) return -1;

		double acc = 0.0;
		for (int i = 0; i < nodes.size() - 1; i++) {
			Node a = nodes.get(i);
			Node b = nodes.get(i + 1);
			if (a == null || b == null) continue;

			double len = a.greatCircleDistance(b);
			if (len <= 0) continue;

			if (alongMeters <= acc + len) {
				return i; // segment is i -> i+1
			}
			acc += len;
		}
		return nodes.size() - 2;
	}
    private static List<Interval> bridgeGaps(List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) return List.of();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Interval i : intervals) {
            min = Math.min(min, i.a);
            max = Math.max(max, i.b);
        }
        return List.of(new Interval(min, max));
    }
}