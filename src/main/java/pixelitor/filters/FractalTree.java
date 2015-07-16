package pixelitor.filters;

import net.jafama.FastMath;
import pixelitor.filters.gui.GradientParam;
import pixelitor.filters.gui.GroupedRangeParam;
import pixelitor.filters.gui.IntChoiceParam;
import pixelitor.filters.gui.ParamSet;
import pixelitor.filters.gui.RangeParam;
import pixelitor.utils.ReseedSupport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Renders a fractal tree
 */
public class FractalTree extends FilterWithParametrizedGUI {
    public static final Color BROWN = new Color(140, 100, 73);
    public static final Color GREEN = new Color(31, 125, 42);
    public static final int QUALITY_BETTER = 1;
    public static final int QUALITY_FASTER = 2;

    private RangeParam iterations = new RangeParam("Age (Iterations)", 1, 16, 10);
    private RangeParam angle = new RangeParam("Angle", 1, 45, 20);
    private RangeParam randomnessParam = new RangeParam("Randomness", 0, 100, 40);
    private GroupedRangeParam width = new GroupedRangeParam("Width",
            new RangeParam[]{
                    new RangeParam("Overall", 100, 300, 100),
                    new RangeParam("Trunk", 100, 500, 200),
            },
            false);

    private RangeParam zoom = new RangeParam("Zoom", 10, 200, 100);
    private RangeParam curvedness = new RangeParam("Curvedness", 0, 50, 10);
    private GroupedRangeParam physics = new GroupedRangeParam("Physics",
            "Gravity", "Wind", -100, 100, 0, false);
    private IntChoiceParam quality = new IntChoiceParam("Quality",
            new IntChoiceParam.Value[]{
                    new IntChoiceParam.Value("Better", QUALITY_BETTER),
                    new IntChoiceParam.Value("Faster", QUALITY_FASTER)
            }, true);

    // precalculated objects for the various depths
    private Stroke[] widthLookup;
    private Color[] colorLookup;
    private Physics[] physicsLookup;
    private boolean doPhysics;
    private boolean leftFirst;
    private boolean hasRandomness;

    GradientParam colors = new GradientParam("Colors",
            new float[]{0.25f, 0.75f},
            new Color[]{BROWN, GREEN}, true);
    private double defaultLength;
    private double randPercent;
    private double lengthDeviation;

    public FractalTree() {
        super("Fractal Tree", false, false);
        setParamSet(new ParamSet(
                iterations,
                zoom,
                randomnessParam,
                curvedness,
                angle,
                physics.setShowLinkedCB(false),
                width.setShowLinkedCB(false),
                colors,
                quality
        ).withAction(ReseedSupport.createAction()));
    }

    @Override
    public BufferedImage doTransform(BufferedImage src, BufferedImage dest) {
        ReseedSupport.reInitialize();
        Random rand = ReseedSupport.getRand();
        leftFirst = true;

        defaultLength = zoom.getValue() / 10.0;
        randPercent = randomnessParam.getValue() / 100.0;
        hasRandomness = randomnessParam.getValue() > 0;
        lengthDeviation = defaultLength * randPercent;

        Graphics2D g = dest.createGraphics();
        if (quality.getValue() == QUALITY_BETTER) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }

        int maxDepth = iterations.getValue();
        widthLookup = new Stroke[maxDepth + 1];
        colorLookup = new Color[maxDepth + 1];

        int gravity = physics.getValue(0);
        int wind = physics.getValue(1);
        if (gravity != 0 || wind != 0) {
            doPhysics = true;
            physicsLookup = new Physics[maxDepth + 1];
        } else {
            doPhysics = false;
            physicsLookup = null;
        }

        for (int depth = 1; depth <= maxDepth; depth++) {
            float w1 = depth * width.getValueAsPercentage(0);
            double trunkWidth = (double) width.getValueAsPercentage(1);
            double base = Math.pow(trunkWidth, 1.0 / (maxDepth - 1));
            double w2 = Math.pow(base, depth - 1);
            float strokeWidth = (float) (w1 * w2);
            float zoomedStrokeWidth = (strokeWidth * zoom.getValue()) / zoom.getDefaultValue();
            widthLookup[depth] = new BasicStroke(zoomedStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            // colors
            float where = ((float) depth) / iterations.getValue();
            int rgb = colors.getValue().getColor(1.0f - where);
            colorLookup[depth] = new Color(rgb);

            float strokeWidth2 = strokeWidth;
            if (doPhysics) {
                physicsLookup[depth] = new Physics(gravity, wind, strokeWidth2);
            }
        }

        float c = curvedness.getValueAsPercentage();
        if (rand.nextBoolean()) {
            c = -c;
        }
        drawTree(g, src.getWidth() / 2.0, src.getHeight(), 270, maxDepth, rand, c);

        g.dispose();

        return dest;
    }

    private void drawTree(Graphics2D g, double x1, double y1, double angle, int depth, Random rand, float c) {
        if (depth == 0) {
            return;
        }
        int nextDepth = depth - 1;
        c = -c; // change the direction of the curvature in each iteration

        if (doPhysics) {
            angle = adjustPhysics(angle, depth);
        }

        double angleRad = Math.toRadians(angle);
        double x2 = x1 + FastMath.cos(angleRad) * depth * calcRandomLength(rand);
        double y2 = y1 + FastMath.sin(angleRad) * depth * calcRandomLength(rand);

        g.setStroke(widthLookup[depth]);
        if (quality.getValue() == QUALITY_BETTER) {
            if (depth == 1) {
                g.setColor(colorLookup[depth]);
            } else {
                g.setPaint(new GradientPaint(
                        (float) x1, (float) y1, colorLookup[depth],
                        (float) x2, (float) y2, colorLookup[(nextDepth)]));
            }
        } else {
            g.setColor(colorLookup[depth]);
        }

        connectPoints(g, x1, y1, x2, y2, c);

        int split = this.angle.getValue();

        double leftBranchAngle = angle - split + calcAngleRandomness(rand);
        double rightBranchAngle = angle + split + calcAngleRandomness(rand);
        leftFirst = !leftFirst;
        if (leftFirst) {
            drawTree(g, x2, y2, leftBranchAngle, nextDepth, rand, c);
            drawTree(g, x2, y2, rightBranchAngle, nextDepth, rand, c);
        } else {
            drawTree(g, x2, y2, rightBranchAngle, nextDepth, rand, c);
            drawTree(g, x2, y2, leftBranchAngle, nextDepth, rand, c);
        }
    }

    private double adjustPhysics(double angle, int depth) {
        assert doPhysics;

        // make sure we have the angle in the range 0-360
        angle += 720;
        angle = angle % 360;

        Physics p = physicsLookup[depth];

        if (angle < 90) {
            angle += (90 - angle) * p.gravityStrength;
            angle -= (angle / 90.0) * p.windStrength;
        } else if (angle < 180) {
            angle -= (angle - 90) * p.gravityStrength;
            angle -= (180 - angle) * p.windStrength;
        } else if (angle < 270) {
            angle -= (270 - angle) * p.gravityStrength;
            angle += (angle - 180) * p.windStrength;
        } else if (angle <= 360) {
            angle += (angle - 270) * p.gravityStrength;
            angle += (360 - angle) * p.windStrength;
        } else {
            throw new IllegalStateException("angle = " + angle);
        }

        return angle;
    }

    private void connectPoints(Graphics2D g, double x1, double y1, double x2, double y2, float c) {
        if (c == 0) {
            Line2D.Double line = new Line2D.Double(x1, y1, x2, y2);
            g.draw(line);
        } else {
            Path2D.Double path = new Path2D.Double();
            path.moveTo(x1, y1);

            double dx = x2 - x1;
            double dy = y2 - y1;

            // center point
            double cx = x1 + dx / 2.0;
            double cy = y1 + dy / 2.0;

            // We calculate only one Bezier control point,
            // and use it for both.
            // The normal vector is -dy, dx.
            double ctrlX = cx - dy * c;
            double ctrlY = cy + dx * c;

            path.curveTo(ctrlX, ctrlY, ctrlX, ctrlY, x2, y2);
            g.draw(path);
        }
    }

    private double calcAngleRandomness(Random rand) {
        if (!hasRandomness) {
            return 0;
        }

        double deviation = (double) 10 * randPercent;
        return -deviation + rand.nextDouble() * 2 * deviation;
    }

    private double calcRandomLength(Random rand) {
        if (!hasRandomness) {
            return defaultLength;
        }

        double minLength = defaultLength - lengthDeviation;

        return (minLength + 2 * lengthDeviation * rand.nextDouble());
    }

    private static class Physics {
        public double gravityStrength;
        public double windStrength;

        private Physics(int gravity, int wind, float strokeWidth2) {
            double effectStrength = 0.02 / strokeWidth2;

            gravityStrength = effectStrength * gravity;
            windStrength = effectStrength * wind;
        }
    }
}