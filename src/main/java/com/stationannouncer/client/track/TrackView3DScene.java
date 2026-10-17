package com.stationannouncer.client.track;

import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;

import java.util.List;

/**
 * World-space geometry of the track editor's 3D pane, built once per scene change and cached:
 * ballast beds, sleepers and rails banked with {@link TrackPath#bank}, piece-boundary ticks, the
 * node marker, a fading ground grid, and (separately, so hovering is cheap) the distance marker.
 *
 * PURE JAVA on purpose (no Minecraft type) so {@code tools/track_test/TrackView3DCheck} can build
 * and rasterise it offline. Coordinates are RELATIVE TO THE FOCUS (floats stay precise far from the
 * world origin): {@code x/z} = world − focus, {@code y} = path height − focusY. The rail TOP sits
 * {@link #RAIL_LIFT} above the path height, so the ballast foot lands on the focus height where the
 * ground grid is drawn.
 *
 * Colours are baked per face with a fixed sun (camera independent), so redraws only project.
 */
public final class TrackView3DScene {
    /** Rail top above the path's y (blocks). The ballast foot is then at the path height. */
    public static final double RAIL_LIFT = 0.40;
    private static final double SLEEPER_SPACING = 0.6;
    private static final double MAX_STEP = 0.5;
    /** Max turned angle per sample (radians). */
    private static final double MAX_TURN = 0.08;
    private static final int MAX_SAMPLES = 9000;
    /** Context tracks are drawn within at least this many blocks of the focus. */
    private static final double CONTEXT_MIN_RADIUS = 40;

    // cross-section (u = lateral, + right of A→B travel; h = height above the rail top)
    private static final double BALLAST_TOP_HALF = 1.15;
    private static final double BALLAST_FOOT_HALF = 1.6;
    private static final double BALLAST_TOP = -0.22;
    private static final double BALLAST_FOOT = -RAIL_LIFT;
    private static final double SLEEPER_HALF_WIDTH = 1.25;
    private static final double SLEEPER_HALF_LENGTH = 0.12;
    private static final double SLEEPER_BOTTOM = -0.25;
    private static final double SLEEPER_TOP = -0.13;
    private static final double RAIL_HALF_WIDTH = 0.06;

    // sun (normalised in the static block)
    private static final double[] SUN = {-0.42, 1.0, -0.3};
    private static final double AMBIENT = 0.46;
    private static final double DIFFUSE = 0.58;

    static {
        final double n = Math.sqrt(SUN[0] * SUN[0] + SUN[1] * SUN[1] + SUN[2] * SUN[2]);
        SUN[0] /= n;
        SUN[1] /= n;
        SUN[2] /= n;
    }

    /** Colours (ARGB). Defaults mirror FlatUi; {@code TrackView3D} copies the live FlatUi values in. */
    public static final class Palette {
        public int background = 0xFF151519;
        public int gridMinor = 0xFF24242B;
        public int gridMajor = 0xFF31313B;
        public int accent = 0xFF3D8BFF;
        public int danger = 0xFFE5484D;
        public int ballastStraight = 0xFF57534E;
        public int ballastArc = 0xFF5B5767;
        public int ballastSpiral = 0xFF4E5C59;
        public int sleeper = 0xFF8E8478;
        public int contextBallast = 0xFF3A3A41;
        public int contextSleeper = 0xFF55555E;
        public int contextRail = 0xFF8C8C97;
        public int tick = 0xFFEDEDF0;
        public int node = 0xFFEDEDF0;
        public int marker = 0xFFFFC53D;
        /** Rails of FOLLOWER tracks (parallel tracks that reshape with the selected one). */
        public int follower = 0xFF4FC3C9;
    }

    private static final int ROLE_CONTEXT = 0;
    private static final int ROLE_SELECTED = 1;
    private static final int ROLE_FOLLOWER = 2;

    /** Flat-coloured quads (4 xyz each) and screen-width lines (2 xyz + width in real pixels). */
    public static final class Mesh {
        public float[] quads = new float[12 * 512];
        public int[] quadColors = new int[512];
        public int quadCount;
        public float[] lines = new float[6 * 128];
        public int[] lineColors = new int[128];
        public float[] lineWidths = new float[128];
        public int lineCount;

        public void clear() {
            quadCount = 0;
            lineCount = 0;
        }

        void quad(double[] a, double[] b, double[] c, double[] d, int color) {
            if (quadCount == quadColors.length) {
                quads = java.util.Arrays.copyOf(quads, quads.length * 2);
                quadColors = java.util.Arrays.copyOf(quadColors, quadColors.length * 2);
            }
            final int o = quadCount * 12;
            put(quads, o, a);
            put(quads, o + 3, b);
            put(quads, o + 6, c);
            put(quads, o + 9, d);
            quadColors[quadCount++] = color;
        }

        void line(double[] a, double[] b, int color, float width) {
            if (lineCount == lineColors.length) {
                lines = java.util.Arrays.copyOf(lines, lines.length * 2);
                lineColors = java.util.Arrays.copyOf(lineColors, lineColors.length * 2);
                lineWidths = java.util.Arrays.copyOf(lineWidths, lineWidths.length * 2);
            }
            final int o = lineCount * 6;
            put(lines, o, a);
            put(lines, o + 3, b);
            lineColors[lineCount] = color;
            lineWidths[lineCount++] = width;
        }

        private static void put(float[] array, int o, double[] p) {
            array[o] = (float) p[0];
            array[o + 1] = (float) p[1];
            array[o + 2] = (float) p[2];
        }
    }

    public final Mesh mesh = new Mesh();
    /** Sleepers on their own so the view can drop the far ones (they alias into moiré). */
    public final Mesh sleepers = new Mesh();
    public final Mesh markerMesh = new Mesh();
    /** Points the default framing must keep in view (xyz triples, relative). */
    public float[] fitPoints = new float[0];
    public int fitPointCount;
    public final Palette palette;
    public final double focusX;
    public final double focusY;
    public final double focusZ;
    private final TrackPath selected;

    /** Bounding sphere to frame on "fit" (relative coordinates). */
    public double fitX;
    public double fitY;
    public double fitZ;
    public double fitRadius = 8;
    /** Chord heading of the selected track (A→B), for the default camera yaw; NaN = none. */
    public double chordHeading = Double.NaN;
    public boolean selectedCloses = true;
    /** Label anchors (relative), null when there is no selected track. */
    public double[] labelA;
    public double[] labelB;
    /** Marker head (relative) and the tilt there; null / NaN when no marker. */
    public double[] markerHead;
    public double markerTilt = Double.NaN;

    private TrackView3DScene(Palette palette, TrackPath selected, double fx, double fy, double fz) {
        this.palette = palette;
        this.selected = selected;
        this.focusX = fx;
        this.focusY = fy;
        this.focusZ = fz;
    }

    /**
     * @param context  other tracks at the node (grey), may contain the selected track's original —
     *                 a context path with the selected one's key is skipped
     * @param selected the draft (highlighted, banked, ticked), may be null
     * @param fx       focus = the node centre in path coordinates (x + 0.5, y, z + 0.5)
     */
    public static TrackView3DScene build(List<TrackPath> context, TrackPath selected, double fx, double fy, double fz, Palette palette) {
        return build(context, selected, List.of(), fx, fy, fz, palette);
    }

    /**
     * As above, plus FOLLOWER tracks: drawn like the selected one (banked, full ballast, never faded)
     * but with {@link Palette#follower} rails, and kept in the default framing. A context path with a
     * follower's key is skipped (the follower is its live draft).
     */
    public static TrackView3DScene build(List<TrackPath> context, TrackPath selected, List<TrackPath> followers,
                                         double fx, double fy, double fz, Palette palette) {
        final TrackView3DScene scene = new TrackView3DScene(palette, selected, fx, fy, fz);
        scene.buildAll(context, followers == null ? List.of() : followers);
        return scene;
    }

    // ------------------------------------------------------------------ building

    private void buildAll(List<TrackPath> context, List<TrackPath> followers) {
        final Samples sel = selected == null ? null : new Samples(selected, focusX, focusY, focusZ);
        double extent = 0;
        if (sel != null) {
            for (int i = 0; i < sel.n; i++) {
                extent = Math.max(extent, sel.dist[i]);
            }
        }
        final double contextRadius = Math.max(CONTEXT_MIN_RADIUS, extent * 1.3);
        final String selectedKey = selected == null || selected.profile == null ? null : selected.profile.key();
        final java.util.Set<String> skipKeys = new java.util.HashSet<>();
        if (selectedKey != null) {
            skipKeys.add(selectedKey);
        }
        final java.util.List<Samples> followerSamples = new java.util.ArrayList<>();
        final java.util.List<TrackPath> followerPaths = new java.util.ArrayList<>();
        for (final TrackPath follower : followers) {
            if (follower == null || follower == selected) {
                continue;
            }
            if (follower.profile != null) {
                skipKeys.add(follower.profile.key());
            }
            followerSamples.add(new Samples(follower, focusX, focusY, focusZ));
            followerPaths.add(follower);
        }

        // fit sphere: the selected track + the node, else the nearby context
        final double[] box = {0, 0, 0, 0, 0, 0}; // min xyz, max xyz around the focus
        if (sel != null) {
            for (int i = 0; i < sel.n; i++) {
                grow(box, sel.x[i], sel.y[i] + RAIL_LIFT, sel.z[i]);
            }
        }
        if (context != null) {
            for (final TrackPath path : context) {
                if (path == null || path == selected || followerPaths.contains(path)
                    || path.profile != null && skipKeys.contains(path.profile.key())) {
                    continue;
                }
                final Samples samples = new Samples(path, focusX, focusY, focusZ);
                trackMesh(samples, path, ROLE_CONTEXT, contextRadius);
                if (sel == null) {
                    for (int i = 0; i < samples.n; i++) {
                        if (samples.dist[i] < 24) {
                            grow(box, samples.x[i], samples.y[i] + RAIL_LIFT, samples.z[i]);
                        }
                    }
                }
            }
        }
        for (int f = 0; f < followerPaths.size(); f++) {
            trackMesh(followerSamples.get(f), followerPaths.get(f), ROLE_FOLLOWER, Double.POSITIVE_INFINITY);
        }
        if (sel != null) {
            selectedCloses = selected.closes();
            trackMesh(sel, selected, ROLE_SELECTED, Double.POSITIVE_INFINITY);
            pieceTicks();
            labelA = new double[]{sel.x[0], sel.y[0] + RAIL_LIFT + 1.2, sel.z[0]};
            final int last = sel.n - 1;
            labelB = new double[]{sel.x[last], sel.y[last] + RAIL_LIFT + 1.2, sel.z[last]};
            final double dx = sel.x[last] - sel.x[0];
            final double dz = sel.z[last] - sel.z[0];
            chordHeading = dx * dx + dz * dz > 1e-6 ? Math.atan2(dz, dx) : sel.heading[0];
        }
        if (sel != null) {
            final int stride = Math.max(1, sel.n / 240);
            for (int i = 0; i < sel.n; i += stride) {
                addFitPoint(sel.x[i], sel.y[i] + RAIL_LIFT, sel.z[i]);
            }
            addFitPoint(sel.x[sel.n - 1], sel.y[sel.n - 1] + RAIL_LIFT, sel.z[sel.n - 1]);
            addFitPoint(labelA[0], labelA[1], labelA[2]);
            addFitPoint(labelB[0], labelB[1], labelB[2]);
            for (final Samples fs : followerSamples) {
                final int fStride = Math.max(1, fs.n / 120);
                for (int i = 0; i < fs.n; i += fStride) {
                    addFitPoint(fs.x[i], fs.y[i] + RAIL_LIFT, fs.z[i]);
                }
                addFitPoint(fs.x[fs.n - 1], fs.y[fs.n - 1] + RAIL_LIFT, fs.z[fs.n - 1]);
            }
        } else {
            addFitPoint(box[0], box[1], box[2]);
            addFitPoint(box[3], box[4], box[5]);
            addFitPoint(box[0], box[1], box[5]);
            addFitPoint(box[3], box[4], box[2]);
        }
        addFitPoint(0, 0, 0);
        addFitPoint(0, RAIL_LIFT + 3, 0);
        fitX = (box[0] + box[3]) / 2;
        fitY = (box[1] + box[4]) / 2;
        fitZ = (box[2] + box[5]) / 2;
        double radius = 0;
        if (sel != null) {
            for (int i = 0; i < sel.n; i++) {
                radius = Math.max(radius, distance(sel.x[i] - fitX, sel.y[i] + RAIL_LIFT - fitY, sel.z[i] - fitZ));
            }
        } else {
            radius = distance(box[3] - box[0], box[4] - box[1], box[5] - box[2]) / 2;
        }
        radius = Math.max(radius, distance(fitX, fitY, fitZ)); // keep the node in frame
        fitRadius = Math.max(4, radius + 1.8);

        nodeMarker();
        grid(Math.max(28, Math.max(contextRadius, fitRadius * 2.2)));
    }

    /**
     * Depth beyond which sleepers are dropped: where their spacing projects to fewer than ~4 real
     * pixels they only shimmer. {@code realPixelsPerUnit} = the GUI scale factor.
     */
    public static double sleeperMaxDepth(double viewHeight, double realPixelsPerUnit) {
        return SLEEPER_SPACING * TrackView3DCamera.focal(viewHeight) * realPixelsPerUnit / 4.0;
    }

    private void addFitPoint(double x, double y, double z) {
        if (fitPointCount * 3 + 3 > fitPoints.length) {
            fitPoints = java.util.Arrays.copyOf(fitPoints, Math.max(48, fitPoints.length * 2));
        }
        fitPoints[fitPointCount * 3] = (float) x;
        fitPoints[fitPointCount * 3 + 1] = (float) y;
        fitPoints[fitPointCount * 3 + 2] = (float) z;
        fitPointCount++;
    }

    /** Rebuilds only the distance marker; NaN clears it. */
    public void setMarker(double s) {
        markerMesh.clear();
        markerHead = null;
        markerTilt = Double.NaN;
        if (selected == null || !Double.isFinite(s)) {
            return;
        }
        final double clamped = Math.max(0, Math.min(selected.length(), s));
        final double[] point = new double[5];
        selected.pointAt(clamped, point);
        final double tilt = selected.tilt(clamped);
        final Frame frame = new Frame(point, tilt, focusX, focusY, focusZ);
        final int color = palette.marker;
        // banked gauge bar across both rails: shows the cant at this point
        box(markerMesh, frame, -1.35, 1.35, 0.0, 0.07, -0.07, 0.07, color);
        // pin along the banked "up" — leans with the tilt
        final double[] foot = frame.at(0, 0.07, 0);
        final double[] head = frame.at(0, 1.9, 0);
        markerMesh.line(foot, head, color, 2.5f);
        octahedron(markerMesh, head, 0.24, color);
        markerHead = head;
        markerTilt = tilt;
    }

    private void trackMesh(Samples p, TrackPath path, int role, double radius) {
        final Palette c = palette;
        final boolean isSelected = role != ROLE_CONTEXT;
        final double fadeStart = radius * 0.72;
        // per-sample cross sections: ballast (4), left rail (4), right rail (4)
        // counter-clockwise from the bottom-right corner, so the skipped closing edge is the bottom
        final double[][] ballast = {{BALLAST_FOOT_HALF, BALLAST_FOOT}, {BALLAST_TOP_HALF, BALLAST_TOP},
            {-BALLAST_TOP_HALF, BALLAST_TOP}, {-BALLAST_FOOT_HALF, BALLAST_FOOT}};
        final double g = TrackProfile.HALF_GAUGE;
        final double w = RAIL_HALF_WIDTH;
        final double[][] railL = {{-g + w, SLEEPER_TOP}, {-g + w, 0}, {-g - w, 0}, {-g - w, SLEEPER_TOP}};
        final double[][] railR = {{g + w, SLEEPER_TOP}, {g + w, 0}, {g - w, 0}, {g - w, SLEEPER_TOP}};
        final int railColor = role == ROLE_SELECTED ? (selectedCloses ? c.accent : c.danger)
            : role == ROLE_FOLLOWER ? (path.closes() ? c.follower : c.danger) : c.contextRail;
        // a hairline along each rail head keeps the track crisp when it is only a few pixels wide
        final int headColor = isSelected ? mix(railColor, 0xFFFFFFFF, 0.3) : mix(c.contextRail, c.background, 0.15);
        final float headWidth = role == ROLE_SELECTED ? 1.3f : role == ROLE_FOLLOWER ? 1.15f : 1f;

        double[][] prevB = null;
        double[][] prevL = null;
        double[][] prevR = null;
        Frame prevFrame = null;
        boolean prevIn = false;
        for (int i = 0; i < p.n; i++) {
            final boolean in = p.dist[i] <= radius;
            final Frame frame = new Frame(p, i);
            final double[][] b = frame.section(ballast);
            final double[][] l = frame.section(railL);
            final double[][] r = frame.section(railR);
            if (in && prevIn) {
                final double sMid = (p.s[i] + p.s[i - 1]) / 2;
                final double fade = fade((p.dist[i] + p.dist[i - 1]) / 2, fadeStart, radius);
                final int bc = isSelected ? ballastColor(path, sMid) : c.contextBallast;
                strip(prevB, b, prevFrame, frame, ballast, false, bc, fade);
                strip(prevL, l, prevFrame, frame, railL, false, railColor, fade);
                strip(prevR, r, prevFrame, frame, railR, false, railColor, fade);
                final int head = mix(headColor, c.background, fade);
                mesh.line(prevFrame.at(-g, 0.012, 0), frame.at(-g, 0.012, 0), head, headWidth);
                mesh.line(prevFrame.at(g, 0.012, 0), frame.at(g, 0.012, 0), head, headWidth);
            }
            // ballast end caps where a run starts or stops
            if (in && (!prevIn || i == 0)) {
                cap(b, frame, -1, isSelected ? ballastColor(path, p.s[i]) : c.contextBallast, fade(p.dist[i], fadeStart, radius));
            }
            if (prevIn && (!in) && prevB != null) {
                cap(prevB, prevFrame, 1, isSelected ? ballastColor(path, p.s[i - 1]) : c.contextBallast, fade(p.dist[i - 1], fadeStart, radius));
            }
            if (in && i == p.n - 1) {
                cap(b, frame, 1, isSelected ? ballastColor(path, p.s[i]) : c.contextBallast, fade(p.dist[i], fadeStart, radius));
            }
            prevB = b;
            prevL = l;
            prevR = r;
            prevFrame = frame;
            prevIn = in;
        }

        // sleepers
        final double length = path.length();
        final double[] point = new double[5];
        final int sleeperColor = isSelected ? c.sleeper : c.contextSleeper;
        for (double s = SLEEPER_SPACING / 2; s < length; s += SLEEPER_SPACING) {
            path.pointAt(s, point);
            final double d = distance(point[0] - focusX, point[1] - focusY, point[2] - focusZ);
            if (d > radius) {
                continue;
            }
            final Frame frame = new Frame(point, path.tilt(s), focusX, focusY, focusZ);
            final int color = mix(sleeperColor, c.background, fade(d, fadeStart, radius));
            box(sleepers, frame, -SLEEPER_HALF_WIDTH, SLEEPER_HALF_WIDTH, SLEEPER_BOTTOM, SLEEPER_TOP,
                -SLEEPER_HALF_LENGTH, SLEEPER_HALF_LENGTH, color);
        }
    }

    private int ballastColor(TrackPath path, double s) {
        if (path.isSpline()) {
            return Math.abs(path.curvature(s)) < 1e-6 ? palette.ballastStraight : palette.ballastArc;
        }
        final int index = path.pieceAt(s);
        final List<TrackProfile.Piece> pieces = path.profile == null ? null : path.profile.pieces;
        if (pieces == null || index < 0 || index >= pieces.size()) {
            return palette.ballastStraight;
        }
        return switch (pieces.get(index).type) {
            case ARC -> palette.ballastArc;
            case SPIRAL -> palette.ballastSpiral;
            default -> palette.ballastStraight;
        };
    }

    /** Joins two cross sections face by face (polygon edges k → k+1, closing edge skipped = no bottom). */
    private void strip(double[][] a, double[][] b, Frame fa, Frame fb, double[][] section, boolean closed, int color, double fade) {
        final int n = section.length;
        final int edges = closed ? n : n - 1;
        for (int k = 0; k < edges; k++) {
            final int k2 = (k + 1) % n;
            // outward normal of edge k→k2 for a counter-clockwise (u right, h up) section: (eh, -eu)
            final double eu = section[k2][0] - section[k][0];
            final double eh = section[k2][1] - section[k][1];
            final double[] na = fa.direction(eh, -eu);
            final double[] nb = fb.direction(eh, -eu);
            final double[] normal = {na[0] + nb[0], na[1] + nb[1], na[2] + nb[2]};
            mesh.quad(a[k], a[k2], b[k2], b[k], mix(shade(color, normal), palette.background, fade));
        }
    }

    private void cap(double[][] section, Frame frame, int sign, int color, double fade) {
        final double[] normal = {frame.tx * sign, 0, frame.tz * sign};
        mesh.quad(section[0], section[1], section[2], section[3], mix(shade(color, normal), palette.background, fade));
    }

    /** Axis-aligned box in a frame's (u, h, along) space, five faces (no bottom). */
    private void box(Mesh target, Frame f, double u0, double u1, double h0, double h1, double a0, double a1, int color) {
        final double[] p000 = f.at(u0, h0, a0);
        final double[] p100 = f.at(u1, h0, a0);
        final double[] p010 = f.at(u0, h1, a0);
        final double[] p110 = f.at(u1, h1, a0);
        final double[] p001 = f.at(u0, h0, a1);
        final double[] p101 = f.at(u1, h0, a1);
        final double[] p011 = f.at(u0, h1, a1);
        final double[] p111 = f.at(u1, h1, a1);
        target.quad(p010, p110, p111, p011, shade(color, f.direction(0, 1)));          // top
        target.quad(p100, p101, p111, p110, shade(color, f.direction(1, 0)));          // right
        target.quad(p000, p010, p011, p001, shade(color, f.direction(-1, 0)));         // left
        target.quad(p001, p011, p111, p101, shade(color, new double[]{f.tx, 0, f.tz})); // forward
        target.quad(p000, p100, p110, p010, shade(color, new double[]{-f.tx, 0, -f.tz}));
    }

    private void pieceTicks() {
        final double[] point = new double[5];
        for (int i = 1; i < selected.pieceCount(); i++) {
            final double s = selected.pieceStart(i);
            selected.pointAt(s, point);
            final Frame f = new Frame(point, selected.tilt(s), focusX, focusY, focusZ);
            final double[] left = f.at(-1.7, 0.05, 0);
            final double[] right = f.at(1.7, 0.05, 0);
            mesh.line(left, right, palette.tick, 2f);
            mesh.line(left, f.at(-1.7, 0.55, 0), palette.tick, 2f);
            mesh.line(right, f.at(1.7, 0.55, 0), palette.tick, 2f);
        }
    }

    private void nodeMarker() {
        final int color = palette.node;
        final double top = RAIL_LIFT + 2.6;
        mesh.line(new double[]{0, RAIL_LIFT, 0}, new double[]{0, top, 0}, color, 2f);
        octahedron(mesh, new double[]{0, top, 0}, 0.3, color);
        // ground ring
        final int segments = 28;
        final double ring = 2.1;
        final int ringColor = mix(color, palette.background, 0.45);
        for (int i = 0; i < segments; i++) {
            final double a0 = 2 * Math.PI * i / segments;
            final double a1 = 2 * Math.PI * (i + 1) / segments;
            mesh.line(new double[]{ring * Math.cos(a0), 0.02, ring * Math.sin(a0)},
                new double[]{ring * Math.cos(a1), 0.02, ring * Math.sin(a1)}, ringColor, 1.5f);
        }
    }

    /** World-aligned grid at the focus height, clipped to a circle and fading toward its rim. */
    private void grid(double radius) {
        double spacing = 1;
        while (spacing * 12 < radius) {
            spacing *= 2;
        }
        final double major = spacing * 4;
        final double y = -0.02;
        final int pieces = 10;
        for (int axis = 0; axis < 2; axis++) {
            final double origin = axis == 0 ? focusX : focusZ;
            final double first = Math.ceil((origin - radius) / spacing) * spacing;
            for (double world = first; world <= origin + radius; world += spacing) {
                final double a = world - origin;
                final double half = Math.sqrt(Math.max(0, radius * radius - a * a));
                if (half < 0.5) {
                    continue;
                }
                final boolean isMajor = Math.abs(world / major - Math.rint(world / major)) < 1e-6;
                final int base = isMajor ? palette.gridMajor : palette.gridMinor;
                for (int k = 0; k < pieces; k++) {
                    final double b0 = -half + 2 * half * k / pieces;
                    final double b1 = -half + 2 * half * (k + 1) / pieces;
                    final double mid = (b0 + b1) / 2;
                    final double d = Math.sqrt(a * a + mid * mid);
                    final int color = mix(base, palette.background, smooth(radius * 0.45, radius, d));
                    final double[] p0 = axis == 0 ? new double[]{a, y, b0} : new double[]{b0, y, a};
                    final double[] p1 = axis == 0 ? new double[]{a, y, b1} : new double[]{b1, y, a};
                    mesh.line(p0, p1, color, isMajor ? 1.4f : 1f);
                }
            }
        }
    }

    private static void octahedron(Mesh target, double[] c, double r, int color) {
        final double[][] axis = {{r, 0, 0}, {0, 0, r}, {-r, 0, 0}, {0, 0, -r}};
        final double[] top = {c[0], c[1] + r * 1.3, c[2]};
        final double[] bottom = {c[0], c[1] - r * 1.3, c[2]};
        for (int i = 0; i < 4; i++) {
            final double[] a = {c[0] + axis[i][0], c[1], c[2] + axis[i][2]};
            final double[] b = {c[0] + axis[(i + 1) % 4][0], c[1], c[2] + axis[(i + 1) % 4][2]};
            final double nx = (axis[i][0] + axis[(i + 1) % 4][0]);
            final double nz = (axis[i][2] + axis[(i + 1) % 4][2]);
            target.quad(a, b, top, top, shade(color, new double[]{nx, r, nz}));
            target.quad(a, bottom, b, b, shade(color, new double[]{nx, -r, nz}));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Evenly spaced samples (finer on tight curves, landing exactly on piece joints), relative to the focus. */
    private static final class Samples {
        int n;
        double[] s;
        double[] x;
        double[] y;
        double[] z;
        double[] heading;
        double[] tilt;
        double[] dist;

        Samples(TrackPath path, double fx, double fy, double fz) {
            final double length = Math.max(0, path.length());
            int capacity = 64;
            s = new double[capacity];
            x = new double[capacity];
            y = new double[capacity];
            z = new double[capacity];
            heading = new double[capacity];
            tilt = new double[capacity];
            dist = new double[capacity];
            final double[] point = new double[5];
            double at = 0;
            int nextPiece = 1;
            while (true) {
                if (n == capacity) {
                    capacity *= 2;
                    s = java.util.Arrays.copyOf(s, capacity);
                    x = java.util.Arrays.copyOf(x, capacity);
                    y = java.util.Arrays.copyOf(y, capacity);
                    z = java.util.Arrays.copyOf(z, capacity);
                    heading = java.util.Arrays.copyOf(heading, capacity);
                    tilt = java.util.Arrays.copyOf(tilt, capacity);
                    dist = java.util.Arrays.copyOf(dist, capacity);
                }
                path.pointAt(at, point);
                s[n] = at;
                x[n] = point[0] - fx;
                y[n] = point[1] - fy;
                z[n] = point[2] - fz;
                heading[n] = point[3];
                tilt[n] = path.tilt(at);
                dist[n] = distance(x[n], y[n], z[n]);
                n++;
                if (at >= length || n >= MAX_SAMPLES) {
                    break;
                }
                final double k = Math.abs(point[4]);
                double step = k > 1e-9 ? Math.min(MAX_STEP, MAX_TURN / k) : MAX_STEP;
                step = Math.max(step, Math.max(0.05, length / (MAX_SAMPLES - 2)));
                double next = Math.min(length, at + step);
                while (nextPiece < path.pieceCount() && path.pieceStart(nextPiece) <= at + 1e-9) {
                    nextPiece++;
                }
                if (nextPiece < path.pieceCount() && path.pieceStart(nextPiece) < next) {
                    next = path.pieceStart(nextPiece);
                }
                at = next;
            }
        }
    }

    /** A banked local frame at one point of a track (relative coordinates). */
    private static final class Frame {
        final double px;
        final double py;
        final double pz;
        /** Tangent (horizontal) and right-of-travel vectors. */
        final double tx;
        final double tz;
        final double rx;
        final double rz;
        final double tilt;
        final double cos;
        final double sin;

        Frame(Samples p, int i) {
            this(p.x[i], p.y[i], p.z[i], p.heading[i], p.tilt[i]);
        }

        Frame(double[] point, double tilt, double fx, double fy, double fz) {
            this(point[0] - fx, point[1] - fy, point[2] - fz, point[3], tilt);
        }

        private Frame(double x, double y, double z, double heading, double tilt) {
            px = x;
            py = y + RAIL_LIFT;
            pz = z;
            tx = Math.cos(heading);
            tz = Math.sin(heading);
            rx = -tz;
            rz = tx;
            this.tilt = tilt;
            cos = Math.cos(Math.toRadians(tilt));
            sin = Math.sin(Math.toRadians(tilt));
        }

        /** Point at lateral {@code u}, height {@code h} above the rail top, {@code along} forward — banked. */
        double[] at(double u, double h, double along) {
            final double[] banked = TrackPath.bank(u, h, tilt);
            return new double[]{px + rx * banked[0] + tx * along, py + banked[1], pz + rz * banked[0] + tz * along};
        }

        double[][] section(double[][] points) {
            final double[][] out = new double[points.length][];
            for (int k = 0; k < points.length; k++) {
                out[k] = at(points[k][0], points[k][1], 0);
            }
            return out;
        }

        /** A cross-section DIRECTION (nu, nh) rolled by the bank (no pivot) into world space. */
        double[] direction(double nu, double nh) {
            final double u = nu * cos + nh * sin;
            final double h = -nu * sin + nh * cos;
            return new double[]{rx * u, h, rz * u};
        }
    }

    private static void grow(double[] box, double x, double y, double z) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }

    static double distance(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double fade(double d, double start, double end) {
        return Double.isInfinite(end) ? 0 : smooth(start, end, d) * 0.85;
    }

    private static double smooth(double a, double b, double v) {
        final double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Lambert shade of {@code argb} for a (not necessarily unit) normal. */
    static int shade(int argb, double[] normal) {
        final double n = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        final double dot = n < 1e-12 ? 0 : (normal[0] * SUN[0] + normal[1] * SUN[1] + normal[2] * SUN[2]) / n;
        final double f = Math.min(1.08, AMBIENT + DIFFUSE * Math.max(0, dot));
        final int r = (int) Math.min(255, ((argb >> 16) & 255) * f);
        final int g = (int) Math.min(255, ((argb >> 8) & 255) * f);
        final int b = (int) Math.min(255, (argb & 255) * f);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, double t) {
        if (t <= 0) {
            return a;
        }
        final double u = Math.min(1, t);
        final int r = (int) Math.round(((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        final int g = (int) Math.round(((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        final int bl = (int) Math.round((a & 255) * (1 - u) + (b & 255) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
