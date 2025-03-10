/*
 * Copyright 2021 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.gui;

import org.jdesktop.swingx.painter.CheckerboardPainter;
import pixelitor.Canvas;
import pixelitor.*;
import pixelitor.gui.utils.Dialogs;
import pixelitor.history.CompositionReplacedEdit;
import pixelitor.history.History;
import pixelitor.layers.*;
import pixelitor.menus.view.ZoomControl;
import pixelitor.menus.view.ZoomLevel;
import pixelitor.selection.SelectionActions;
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;
import pixelitor.tools.util.PPoint;
import pixelitor.tools.util.PRectangle;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Lazy;
import pixelitor.utils.Messages;
import pixelitor.utils.debug.DebugNodes;
import pixelitor.utils.test.Assertions;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import static java.awt.Color.BLACK;
import static java.lang.String.format;
import static pixelitor.utils.Threads.calledOnEDT;
import static pixelitor.utils.Threads.threadInfo;

/**
 * The GUI component that shows a {@link Composition} inside a {@link ViewContainer}.
 */
public class View extends JComponent implements MouseListener, MouseMotionListener {
    private Composition comp;
    private Canvas canvas;
    private ZoomLevel zoomLevel = ZoomLevel.Z100;
    private double scaling = 1.0f;

    private ViewContainer viewContainer = null;
    private LayersPanel layersPanel;
    private MaskViewMode maskViewMode;
    private Navigator navigator;

    private static final CheckerboardPainter checkerBoardPainter
        = ImageUtils.createCheckerboardPainter();

    // The start coordinates of the canvas in component space,
    // (bigger than zero if the CompositionView is bigger
    // than the canvas, and the canvas has to be centralized)
    private double canvasStartX;
    private double canvasStartY;

    private final Lazy<AffineTransform> imToCo = Lazy.of(this::createImToCoTransform);
    private final Lazy<AffineTransform> coToIm = Lazy.of(this::createCoToImTransform);

    private static boolean showPixelGrid = false;

    public View(Composition comp) {
        assert !RunContext.isUnitTesting() : "Swing component in unit test";
        assert comp != null;

        this.comp = comp;
        canvas = comp.getCanvas();
        comp.setView(this);

        ZoomLevel fitZoom = ZoomLevel.calcZoom(canvas, null, false);
        setZoom(fitZoom);

        layersPanel = new LayersPanel();

        addListeners();
    }

    public void replaceJustReloadedComp(Composition newComp) {
        assert calledOnEDT() : threadInfo();
        assert newComp != comp;

        // do this before actually replacing so that the old comp is
        // deselected before its view is set to null
        History.add(new CompositionReplacedEdit(
            "Reload", this, comp, newComp, null, true));
        replaceComp(newComp, MaskViewMode.NORMAL, true);

        // the view was active when the reload started, but since the
        // reload was asynchronous, this could have changed
        if (isActive()) {
            SelectionActions.setEnabled(false, newComp);
        }

        String msg = format("The image <b>%s</b> was reloaded from the file <b>%s</b>.",
            newComp.getName(), newComp.getFile().getAbsolutePath());
        Messages.showInStatusBar(msg);
    }

    // the simple form of replacing, used by multi-layer edits
    public void replaceComp(Composition newComp) {
        replaceComp(newComp, getMaskViewMode(), false);
    }

    public void replaceComp(Composition newComp,
                            MaskViewMode newMaskViewMode,
                            boolean reloaded) {
        assert newComp != null;

        Composition oldComp = comp;
        comp = newComp;

        newComp.setView(this);
        newComp.createLayerUIs();
        canvas = newComp.getCanvas();

        // recreate the layer buttons
        layersPanel = new LayersPanel();
        newComp.addAllLayersToGUI();
        LayersContainer.showLayersFor(this);
        Layers.activeLayerChanged(newComp.getActiveLayer(), false);

        oldComp.setView(null);

        newMaskViewMode.activate(this, newComp.getActiveLayer());
        repaintNavigator(true);

        Tools.currentTool.compReplaced(oldComp, newComp, reloaded);

        revalidate(); // make sure the scrollbars are OK if the new comp has a different size
        canvasCoSizeChanged();
        repaint();
        HistogramsPanel.updateFrom(newComp);
    }

    private void addListeners() {
        addMouseListener(this);
        addMouseMotionListener(this);

        MouseZoomMethod.CURRENT.installOnView(this);
    }

    public boolean isDirty() {
        return comp.isDirty();
    }

    public boolean isActive() {
        return OpenImages.getActiveView() == this;
    }

    @Override
    public Dimension getPreferredSize() {
        if (comp.isEmpty()) {
            return super.getPreferredSize();
        } else {
            return canvas.getCoSize();
        }
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Tools.EventDispatcher.mouseClicked(e, this);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        Tools.EventDispatcher.mouseEntered(e, this);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        Tools.EventDispatcher.mouseExited(e, this);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Tools.EventDispatcher.mousePressed(e, this);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        Tools.EventDispatcher.mouseReleased(e, this);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Tools.EventDispatcher.mouseDragged(e, this);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        Tools.EventDispatcher.mouseMoved(e, this);
    }

    public void setViewContainer(ViewContainer container) {
        viewContainer = container;
        updateContainerSize();
    }

    public ViewContainer getViewContainer() {
        return viewContainer;
    }

    public void close() {
        if (viewContainer != null) {
            // this will also cause the calling of ImageComponents.imageClosed via
            // ImageFrame.internalFrameClosed
            viewContainer.close();
        }
        comp.dispose();
    }

    public void activateUI(boolean selectWindow) {
        if (selectWindow) {
            // it might be necessary to programmatically select a window:
            // for example if in the frames UI a window is closed,
            // another one is set to be the active one
            getViewContainer().select();
        }
        LayersContainer.showLayersFor(this);
    }

    public double getScaling() {
        return scaling;
    }

    public void updateTitle() {
        if (viewContainer != null) {
            viewContainer.updateTitle(this);
        }
    }

    // used only for the frames ui
    public String createTitleWithZoom() {
        return comp.getName() + " - " + zoomLevel;
    }

    public ZoomLevel getZoomLevel() {
        return zoomLevel;
    }

    public void removeLayerUI(LayerUI ui) {
        layersPanel.removeLayerButton((LayerButton) ui);
    }

    public Composition getComp() {
        return comp;
    }

    @Override
    public String getName() {
        return comp.getName();
    }

    public void changeLayerButtonOrder(int oldIndex, int newIndex) {
        layersPanel.changeLayerButtonOrder(oldIndex, newIndex);
    }

    public CanvasMargins getCanvasMargins() {
        return new CanvasMargins(canvasStartY, canvasStartX, canvasStartY, canvasStartX);
    }

    @Override
    public void paint(Graphics g) {
        try {
            // no borders, no children, double-buffering is happening
            // in the parent
            paintComponent(g);
        } catch (OutOfMemoryError e) {
            Dialogs.showOutOfMemoryDialog(e);
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        Shape originalClip = g.getClip();

        Graphics2D g2 = (Graphics2D) g;

        int canvasCoWidth = canvas.getCoWidth();
        int canvasCoHeight = canvas.getCoHeight();

        Rectangle canvasClip = setVisibleCanvasClip(g,
            canvasStartX, canvasStartY,
            canvasCoWidth, canvasCoHeight);

        // make a copy of the transform object
        var componentTransform = g2.getTransform();

        // TODO casting to int seems necessary, otherwise the
        //   checkerboard and the image might be painted on slightly different
        //   coordinates and this is visible when using a dark theme
        g2.translate((int) canvasStartX, (int) canvasStartY);

        boolean showMask = maskViewMode.showMask();
        if (!showMask) {
            checkerBoardPainter.paint(g2, this, canvasCoWidth, canvasCoHeight);
        }

        g2.scale(scaling, scaling);
        // after the translation and scaling, we are in "image space"

        if (showMask) {
            LayerMask mask = comp.getActiveLayer().getMask();
            assert mask != null : "no mask in " + maskViewMode;
            mask.paintLayerOnGraphics(g2, true);
        } else {
            BufferedImage compositeImage = comp.getCompositeImage();
            g2.drawImage(compositeImage, 0, 0, null);

            if (maskViewMode.showRuby()) {
                LayerMask mask = comp.getActiveLayer().getMask();
                assert mask != null : "no mask in " + maskViewMode;
                mask.paintAsRubylith(g2);
            }
        }

        Tool currentTool = Tools.getCurrent();
        // possibly allow a larger clip for the selections and tools
        currentTool.setClip(g2, this, originalClip);

        comp.paintSelection(g2);

        // restore the original transform
        g2.setTransform(componentTransform);
        g2.setClip(originalClip);
        // now we are back in "component space"

        comp.drawGuides(g2);

        if (isActive()) {
            currentTool.paintOverImage(g2, comp);
        }

        g2.setClip(canvasClip);

        if (showPixelGrid && allowPixelGrid()) {
            drawPixelGrid(g2);
        }

        g2.setClip(originalClip);
    }

    public void paintImmediately() {
        paintImmediately(getX(), getY(), getWidth(), getHeight());
    }

    public boolean allowPixelGrid() {
        // for some reason the pixel grid is very slow if there is
        // a selection visible, so don't show it
        return zoomLevel.allowPixelGrid() && !comp.showsSelection();
    }

    private void drawPixelGrid(Graphics2D g2) {
        g2.setXORMode(BLACK);
        double pixelSize = zoomLevel.getViewScale();

        Rectangle r = getVisiblePart();

        int startX = r.x;
        int endX = r.x + r.width;
        int startY = r.y;
        int endY = r.y + r.height;

        // vertical lines
        double skipVer = Math.ceil(startX / pixelSize);
        for (double i = pixelSize * skipVer; i < endX; i += pixelSize) {
            int x = (int) (canvasStartX + i);
            g2.drawLine(x, startY, x, endY);
        }

        // horizontal lines
        double skipHor = Math.ceil(startY / pixelSize);
        for (double i = skipHor * pixelSize; i < endY; i += pixelSize) {
            int y = (int) (canvasStartY + i);
            g2.drawLine(startX, y, endX, y);
        }
    }

    public static void setShowPixelGrid(boolean newValue) {
        if (showPixelGrid == newValue) {
            return;
        }
        showPixelGrid = newValue;
        if (newValue) {
            ImageArea.pixelGridEnabled();
        } else {
            OpenImages.repaintVisible();
        }
    }

    /**
     * Makes sure that not the whole area is repainted, only the canvas,
     * and only inside the visible area of scrollbars
     */
    private static Rectangle setVisibleCanvasClip(Graphics g,
                                                  double canvasStartX, double canvasStartY,
                                                  int maxWidth, int maxHeight) {
        // if there are scollbars, this is the visible area
        Rectangle clipBounds = g.getClipBounds();

        Rectangle imageRect = new Rectangle(
            (int) canvasStartX, (int) canvasStartY,
            maxWidth, maxHeight);

        // now we are definitely not drawing neither outside
        // the canvas nor outside the scrollbars visible area
        clipBounds = clipBounds.intersection(imageRect);

        g.setClip(clipBounds);
        return clipBounds;
    }

    /**
     * Repaints only a region of the image
     */
    public void repaintRegion(PPoint start, PPoint end, double thickness) {
        double startX = start.getCoX();
        double startY = start.getCoY();
        double endX = end.getCoX();
        double endY = end.getCoY();

        // make sure that the start coordinates are smaller
        if (endX < startX) {
            double tmp = startX;
            startX = endX;
            endX = tmp;
        }
        if (endY < startY) {
            double tmp = startY;
            startY = endY;
            endY = tmp;
        }

        // the thickness is derived from the brush radius, therefore
        // it still needs to be converted into component space
        thickness = scaling * thickness;

        startX = startX - thickness;
        endX = endX + thickness;
        startY = startY - thickness;
        endY = endY + thickness;

        // add 1 to the width and height because
        // casting to int will round them downwards
        double repWidth = endX - startX + 1;
        double repHeight = endY - startY + 1;

        repaint((int) startX, (int) startY, (int) repWidth, (int) repHeight);
    }

    /**
     * Repaints only a region of the image
     */
    public void repaintRegion(PRectangle area) {
        repaint(area.getCo());
    }

//    /**
//     * All repaint requests go through this method,
//     * it can be uncommented to debug repainting.
//     */
//    @Override
//    public void repaint(long tm, int x, int y, int width, int height) {
//        System.out.printf("View::repaint: tm = %d, x = %d, y = %d, width = %d, height = %d%n", tm, x, y, width, height);
//        Thread.dumpStack();
//        super.repaint(tm, x, y, width, height);
//    }

    public void ensurePositiveLocation() {
        if (viewContainer != null) {
            viewContainer.ensurePositiveLocation();
        }
    }

    public MaskViewMode getMaskViewMode() {
        return maskViewMode;
    }

    public boolean setMaskViewMode(MaskViewMode maskViewMode) {
        // it is important not to call this directly,
        // it should be a part of a mask activation
        assert Assertions.callingClassIs("MaskViewMode");
        assert maskViewMode.canBeAssignedTo(comp.getActiveLayer());

        MaskViewMode oldMode = this.maskViewMode;
        this.maskViewMode = maskViewMode;

        boolean change = oldMode != maskViewMode;
        if (change) {
            repaint();
        }
        return change;
    }

    public void canvasCoSizeChanged() {
        assert ConsistencyChecks.imageCoversCanvas(comp);

        updateContainerSize();
        updateCanvasLocation();
    }

    private void updateContainerSize() {
        if (viewContainer instanceof ImageFrame) {
            ImageFrame frame = (ImageFrame) viewContainer;
            frame.setToCanvasSize();
        }
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public double getCanvasStartX() {
        return canvasStartX;
    }

    public double getCanvasStartY() {
        return canvasStartY;
    }

    public void setZoom(AutoZoom autoZoom) {
        ZoomLevel fittingZoom = ZoomLevel.calcZoom(canvas, autoZoom, true);
        setZoom(fittingZoom);
    }

    public void setZoom(ZoomLevel newZoom) {
        setZoom(newZoom, null);
    }

    /**
     * Sets the new zoom level
     */
    public void setZoom(ZoomLevel newZoom, Point mousePos) {
        ZoomLevel oldZoom = zoomLevel;
        if (oldZoom == newZoom) {
            return;
        }

        this.zoomLevel = newZoom;
        scaling = newZoom.getViewScale();
        canvas.recalcCoSize(this);

        if (ImageArea.currentModeIs(ImageArea.Mode.FRAMES)) {
            updateTitle();
        } else {
            // otherwise the scrollbars don't appear
            // when using the tabbed UI
            revalidate();
        }

        if (viewContainer != null) {
            moveScrollbarsAfterZoom(oldZoom, newZoom, mousePos);
        }

        if (isActive()) {
            ZoomControl.get().changeZoom(zoomLevel);
            zoomLevel.getMenuItem().setSelected(true);
        }
    }

    private void moveScrollbarsAfterZoom(ZoomLevel oldZoom,
                                         ZoomLevel newZoom,
                                         Point mousePos) {
        Rectangle visiblePart = getVisiblePart();
        Point zoomOrigin;
        if (mousePos != null) { // started from a mouse event
            zoomOrigin = mousePos;
        } else {
            int cx = visiblePart.x + visiblePart.width / 2;
            int cy = visiblePart.y + visiblePart.height / 2;

            zoomOrigin = new Point(cx, cy);
        }
        // the x, y coordinates were generated BEFORE the zooming,
        // now find the corresponding coordinates after zooming
        Point imOrigin = fromComponentToImageSpace(zoomOrigin, oldZoom);
        zoomOrigin = fromImageToComponentSpace(imOrigin, newZoom);

        Rectangle areaThatShouldBeVisible = new Rectangle(
            zoomOrigin.x - visiblePart.width / 2,
            zoomOrigin.y - visiblePart.height / 2,
            visiblePart.width,
            visiblePart.height
        );

        scrollRectToVisible(areaThatShouldBeVisible);
        repaint();
    }

    public void increaseZoom() {
        increaseZoom(null);
    }

    public void increaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomIn();
        setZoom(newZoom, mousePos);
    }

    public void decreaseZoom() {
        decreaseZoom(null);
    }

    public void decreaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomOut();
        setZoom(newZoom, mousePos);
    }

    // it seems that all Swing resizing goes through this method, so we don't
    // have to listen to componentResized events, which might come too late
    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);

        updateCanvasLocation();
        repaint();
    }

    private void updateCanvasLocation() {
        int myWidth = getWidth();
        int myHeight = getHeight();
        int canvasCoWidth = canvas.getCoWidth();
        int canvasCoHeight = canvas.getCoHeight();

        // ensure this component is at least as big as the canvas
        if (myWidth < canvasCoWidth || myHeight < canvasCoHeight) {
            setSize(Math.max(myWidth, canvasCoWidth),
                Math.max(myHeight, canvasCoHeight));

            // setSize will call this method again after setting the size
            return;
        }

        // centralize the canvas within this component
        canvasStartX = (myWidth - canvasCoWidth) / 2.0;
        canvasStartY = (myHeight - canvasCoHeight) / 2.0;

        // one can zoom an inactive image with the mouse wheel,
        // but the tools are interacting only with the active image
        if (isActive()) {
            Tools.coCoordsChanged(this);
        }
        comp.coCoordsChanged();

        imToCo.invalidate();
        coToIm.invalidate();
    }

    public double componentXToImageSpace(double coX) {
        return ((coX - canvasStartX) / scaling);
    }

    public double componentYToImageSpace(double coY) {
        return ((coY - canvasStartY) / scaling);
    }

    public double imageXToComponentSpace(double imX) {
        return canvasStartX + imX * scaling;
    }

    public double imageYToComponentSpace(double imY) {
        return canvasStartY + imY * scaling;
    }

    public Point2D componentToImageSpace(Point2D co) {
        return new Point2D.Double(
            componentXToImageSpace(co.getX()),
            componentYToImageSpace(co.getY()));
    }

    public Point2D imageToComponentSpace(Point2D im) {
        return new Point2D.Double(
            imageXToComponentSpace(im.getX()),
            imageYToComponentSpace(im.getY()));
    }

    private Point fromComponentToImageSpace(Point co, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
            (int) ((co.x - canvasStartX) / zoomViewScale),
            (int) ((co.y - canvasStartY) / zoomViewScale)
        );
    }

    private Point fromImageToComponentSpace(Point im, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
            (int) (canvasStartX + im.x * zoomViewScale),
            (int) (canvasStartY + im.y * zoomViewScale)
        );
    }

    public Rectangle2D componentToImageSpace(Rectangle2D co) {
        return new Rectangle.Double(
            componentXToImageSpace(co.getX()),
            componentYToImageSpace(co.getY()),
            co.getWidth() / scaling,
            co.getHeight() / scaling
        );
    }

    public Rectangle imageToComponentSpace(Rectangle2D im) {
        return new Rectangle(
            (int) imageXToComponentSpace(im.getX()),
            (int) imageYToComponentSpace(im.getY()),
            (int) (im.getWidth() * scaling),
            (int) (im.getHeight() * scaling)
        );
    }

    public AffineTransform getImageToComponentTransform() {
        return imToCo.get();
    }

    private AffineTransform createImToCoTransform() {
        var at = new AffineTransform();
        at.translate(canvasStartX, canvasStartY);
        at.scale(scaling, scaling);
        return at;
    }

    public AffineTransform getComponentToImageTransform() {
        return coToIm.get();
    }

    private AffineTransform createCoToImTransform() {
        var at = new AffineTransform();
        double s = 1.0 / scaling;
        at.scale(s, s);
        at.translate(-canvasStartX, -canvasStartY);
        return at;
    }

    /**
     * Returns how much of this {@link View} is currently
     * visible considering that the JScrollPane might show
     * only a part of it
     */
    public Rectangle getVisiblePart() {
        return viewContainer.getScrollPane().getViewport().getViewRect();
    }

    public void addLayerToGUI(Layer newLayer, int newLayerIndex) {
        assert calledOnEDT() : threadInfo();

        // can be casted outside unit tests
        LayerButton layerButton = (LayerButton) newLayer.createUI();

        try {
            // otherwise loading multi-layer files makes the comp dirty
            layerButton.setUserInteraction(false);
            layersPanel.addLayerButton(layerButton, newLayerIndex);
            layerButton.updateSelectionState();
        } finally {
            layerButton.setUserInteraction(true);
        }

        if (isActive()) {
            Layers.numLayersChanged(comp, comp.getNumLayers());
        }

        if (newLayer instanceof ImageLayer) {
            ImageLayer imageLayer = (ImageLayer) newLayer;
            imageLayer.updateIconImage();
        }
        if (newLayer.hasMask()) {
            newLayer.getMask().updateIconImage();
        }
    }

    public boolean activeIsDrawable() {
        return comp.activeIsDrawable();
    }

    /**
     * The return value is changed only in unit tests
     */
    @SuppressWarnings({"MethodMayBeStatic", "SameReturnValue"})
    public boolean isMock() {
        return false;
    }

    public LayersPanel getLayersPanel() {
        return layersPanel;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    public void repaintNavigator(boolean viewSizeChanged) {
        assert calledOnEDT() : threadInfo();

        if (navigator != null) {
            if (viewSizeChanged) {
                // defer until all
                // pending events have been processed
                SwingUtilities.invokeLater(() -> {
                    if (navigator != null) { // check again for safety
                        // will also repaint
                        navigator.recalculateSize(this, false,
                            true, false);
                    }
                });
            } else {
                // call here, painting calls will be coalesced anyway
                navigator.repaint();
            }
        }
    }

    /**
     * Returns the bounds of the visible part of the canvas
     * in screen coordinates
     */
    public Rectangle getVisibleCanvasBoundsOnScreen() {
        Rectangle canvasRelativeToView = new Rectangle(
            (int) canvasStartX, (int) canvasStartY,
            canvas.getCoWidth(), canvas.getCoHeight());

        // take scrollbars into account
        Rectangle retVal = canvasRelativeToView.intersection(getVisiblePart());
        if (retVal.isEmpty()) {
            throw new IllegalStateException("canvas not visible");
        }

        // transform into screen coordinates
        Point onScreen = getLocationOnScreen();
        retVal.translate(onScreen.x, onScreen.y);
        return retVal;
    }

    @Override
    public String toString() {
        var node = DebugNodes.createViewNode("view", this);
        return node.toDetailedString();
    }
}
