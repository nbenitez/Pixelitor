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

package pixelitor;

import pixelitor.compactions.EnlargeCanvas;
import pixelitor.gui.HistogramsPanel;
import pixelitor.gui.PixelitorWindow;
import pixelitor.gui.View;
import pixelitor.gui.utils.Dialogs;
import pixelitor.guides.Guides;
import pixelitor.guides.GuidesChangeEdit;
import pixelitor.history.*;
import pixelitor.io.FileFormat;
import pixelitor.io.IOTasks;
import pixelitor.io.SaveSettings;
import pixelitor.layers.*;
import pixelitor.menus.file.RecentFilesMenu;
import pixelitor.selection.Selection;
import pixelitor.selection.SelectionActions;
import pixelitor.selection.ShapeCombination;
import pixelitor.tools.Tools;
import pixelitor.tools.move.MoveMode;
import pixelitor.tools.pen.Path;
import pixelitor.tools.pen.Paths;
import pixelitor.tools.util.PPoint;
import pixelitor.tools.util.PRectangle;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.VisibleForTesting;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE;
import static java.lang.String.format;
import static pixelitor.Composition.ImageChangeActions.FULL;
import static pixelitor.Composition.LayerAdder.Position.*;
import static pixelitor.io.FileUtils.stripExtension;
import static pixelitor.utils.Threads.*;
import static pixelitor.utils.Utils.createCopyName;

/**
 * An image composition consisting of multiple layers
 */
public class Composition implements Serializable {
    // serialization is used for saving in the pxc format
    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    private final List<Layer> layerList = new ArrayList<>();
    private Layer activeLayer;

    // a counter for the names of new layers
    private int newLayerCount = 1;

    private final Canvas canvas;
    private Paths paths;
    private Guides guides;

    //
    // transient variables from here
    //
    private transient File file;
    private transient boolean dirty = false;

    private transient BufferedImage compositeImage;

    private transient View view;

    private transient Selection selection;

    // a temporary, new selection which is currently built
    // by dragging with a tool, but not finalized yet
    private transient Selection builtSelection;

    /**
     * The constructor is private: a {@link Composition}
     * can be created either with one of the static factory
     * methods or through deserialization of pxc files
     */
    private Composition(Canvas canvas) {
        assert canvas != null;
        this.canvas = canvas;
    }

    /**
     * Creates a single-layered composition from the given image
     */
    public static Composition fromImage(BufferedImage img, File file, String name) {
        assert img != null;

        img = ImageUtils.toSysCompatibleImage(img);
        Canvas canvas = new Canvas(img.getWidth(), img.getHeight());
        var comp = new Composition(canvas);
        comp.addBaseLayer(img);

        if (file != null) {
            comp.setFile(file); // also sets the name based on the file name
        } else if (name != null) {
            comp.setName(name);
        } else {
            // one of the file and name arguments must be given
            throw new IllegalArgumentException("no name could be set");
        }
        assert comp.getName() != null;
        return comp;
    }

    /**
     * Creates an empty composition (no layers, no name)
     */
    public static Composition createEmpty(int width, int height) {
        Canvas canvas = new Canvas(width, height);
        return new Composition(canvas);
    }

    /**
     * Creates and returns a deep copy of this composition.
     */
    public Composition copy(boolean forUndo, boolean copySelection) {
        var canvasCopy = new Canvas(canvas);
        var compCopy = new Composition(canvasCopy);

        // copy layers
        for (Layer layer : layerList) {
            var layerCopy = layer.duplicate(true);
            layerCopy.setComp(compCopy);

            compCopy.layerList.add(layerCopy);
            if (layer == activeLayer) {
                compCopy.activeLayer = layerCopy;
            }
        }

        compCopy.newLayerCount = newLayerCount;

        if (copySelection && selection != null) {
            compCopy.setSelectionRef(new Selection(selection, forUndo));
        }
        if (paths != null) {
            compCopy.paths = paths.deepCopy(compCopy);
        }
        if (forUndo) {
            compCopy.dirty = dirty;
            compCopy.file = file;
            compCopy.name = name;
            compCopy.view = view;
            // the new guides are set in the action that needed the undo
        } else { // duplicate
            compCopy.dirty = true;
            compCopy.file = null;
            compCopy.name = createCopyName(stripExtension(name));
            compCopy.view = null;
            if (guides != null) {
                compCopy.guides = guides.copyForNewComp(view);
            }
        }

        assert compCopy.checkInvariant();

        return compCopy;
    }

    public void createLayerUIs() {
        for (Layer layer : layerList) {
            layer.createUI();
        }
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        // init transient variables
        compositeImage = null; // will be set when needed
        file = null; // will be set later
        dirty = false;
        view = null; // will be set later
        selection = null; // the selection is not saved
        builtSelection = null;

        in.defaultReadObject();
    }

    public View getView() {
        return view;
    }

    public void setView(View view) {
        this.view = view;
        if (view != null) {
            canvas.recalcCoSize(view);
        }

        if (selection != null) { // can happen when duplicating
            if (view == null) {
                throw new IllegalStateException(); // should deselect first
            } else {
                selection.setView(view);
            }
        }
        if (paths != null) {
            if (view != null) { // the view can be null when reloading
                paths.setView(view);
            }
        }
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public Rectangle getCanvasBounds() {
        return canvas.getBounds();
    }

    public int getCanvasWidth() {
        return canvas.getWidth();
    }

    public int getCanvasHeight() {
        return canvas.getHeight();
    }

    public Shape clipToCanvasBounds(Shape shape) {
        return canvas.clip(shape);
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDirty() {
        return dirty;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        if (view != null) {
            view.updateTitle();
            PixelitorWindow.get().updateTitle(this);
        }
    }

    public String generateNewLayerName() {
        return "layer " + newLayerCount++;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
        setName(file.getName());
    }

    public boolean hasSameFileAs(Composition other, boolean allowNull) {
        if (file == null) {
            if (allowNull) {
                return other.file == null;
            } else {
                return false;
            }
        } else {
            return file.equals(other.file);
        }
    }

    public boolean isEmpty() {
        return layerList.isEmpty();
    }

    private void addBaseLayer(BufferedImage baseLayerImage) {
        var newLayer = new ImageLayer(this,
            baseLayerImage, generateNewLayerName());

        addLayerInInitMode(newLayer);
    }

    /**
     * "Init mode" means that this is part of the composition construction,
     * the layer is not added as a result of a user interaction
     */
    public void addLayerInInitMode(Layer newLayer) {
        new LayerAdder(this).compInitMode().add(newLayer);
    }

    public ImageLayer addNewEmptyLayer(String name, boolean bellowActive) {
        var newLayer = ImageLayer.createEmpty(this, name);
        new LayerAdder(this)
            .withHistory("New Empty Layer")
            .atPosition(bellowActive ? BELLOW_ACTIVE : ABOVE_ACTIVE)
            .noRefresh()
            .add(newLayer);

//        newLayer.updateIconImage();
        return newLayer;
    }

    public void addExternalImageAsNewLayer(BufferedImage image, String layerName, String historyName) {
        var newLayer = ImageLayer.fromExternalImage(image, this, layerName);
        new LayerAdder(this)
            .withHistory(historyName)
            .add(newLayer);
    }

    public void addNewLayerFromComposite() {
        var newLayer = new ImageLayer(this,
            getCompositeImage(), "Composite");

        new LayerAdder(this)
            .withHistory("New Layer from Visible")
            .noRefresh()
            .atIndex(layerList.size())
            .add(newLayer);
    }

    public void duplicateActiveLayer() {
        Layer duplicate = activeLayer.duplicate(false);
        if (duplicate == null) {
            // there was an out of memory error
            return;
        }
        new LayerAdder(this)
            .withHistory("Duplicate Layer")
            .add(duplicate);
        assert checkInvariant();
    }

    public void flattenImage() {
        assert isActive();

        if (layerList.size() < 2) {
            return;
        }

        int numLayers = getNumLayers();
        BufferedImage bi = getCompositeImage();

        Layer flattened = new ImageLayer(this, bi, "flattened");
        new LayerAdder(this)
            .atIndex(numLayers) // add to the top
            .noRefresh()
            .add(flattened);

        for (int i = numLayers - 1; i >= 0; i--) { // delete the rest
            deleteLayer(layerList.get(i), false);
        }

        Layers.numLayersChanged(this, 1);
        History.add(new NotUndoableEdit("Flatten Image", this));
    }

    public void addAllLayersToGUI() {
        assert checkInvariant();

        // when adding layer buttons, the last layer gets active
        // but here we don't want to change the selected layer
        Layer previousActiveLayer = activeLayer;

        layerList.forEach(this::addLayerToGUI);

        setActiveLayer(previousActiveLayer);
    }

    private void addLayerToGUI(Layer layer) {
        int layerIndex = layerList.indexOf(layer);
        view.addLayerToGUI(layer, layerIndex);
    }

    public boolean canMergeDown(Layer layer) {
        int index = layerList.indexOf(layer);
        if (index > 0 && layer.isVisible()) {
            Layer bellow = layerList.get(index - 1);
            if (bellow instanceof ImageLayer && bellow.isVisible()) {
                return true;
            }
        }
        return false;
    }

    public void mergeActiveLayerDown() {
        assert checkInvariant();

        if (canMergeDown(activeLayer)) {
            mergeDown(activeLayer);
        }
    }

    // this method assumes that canMergeDown() previously returned true
    public void mergeDown(Layer layer) {
        int layerIndex = layerList.indexOf(layer);
        var bellowLayer = (ImageLayer) layerList.get(layerIndex - 1);

        var bellowImage = bellowLayer.getImage();
        var maskViewModeBefore = view.getMaskViewMode();
        var imageBefore = ImageUtils.copyImage(bellowImage);

        // apply the effect of the merged layer to the image of the image layer
        Graphics2D g = bellowImage.createGraphics();
        g.translate(-bellowLayer.getTx(), -bellowLayer.getTy());
        BufferedImage result = layer.applyLayer(g, bellowImage, false);
        if (result != null) {  // this was an adjustment
            bellowLayer.setImage(result);
        }
        g.dispose();

        bellowLayer.updateIconImage();

        deleteLayer(layer, false);

        History.add(new MergeDownEdit(this, layer,
            bellowLayer, imageBefore, maskViewModeBefore, layerIndex));
    }

    public void deleteActiveLayer(boolean addToHistory) {
        deleteLayer(activeLayer, addToHistory);
    }

    public void deleteLayer(Layer layer, boolean addToHistory) {
        if (layerList.size() < 2) {
            throw new IllegalStateException("there are " + layerList.size() + " layers");
        }

        int layerIndex = layerList.indexOf(layer);

        if (addToHistory) {
            History.add(new DeleteLayerEdit(this, layer, layerIndex));
        }

        layerList.remove(layer);

        if (layer == activeLayer) {
            if (layerIndex > 0) {
                setActiveLayer(layerList.get(layerIndex - 1));
            } else {  // deleted the fist layer, set the new first layer as active
                setActiveLayer(layerList.get(0));
            }
        }

        LayerUI ui = layer.getUI();
        if (ui != null) { // can be null if part of layer rasterization
            view.removeLayerUI(ui);

            if (isActive()) {
                Layers.numLayersChanged(this, layerList.size());
            }

            imageChanged();
        }
    }

    /**
     * Replaces a layer with another, while keeping its position, mask, ui
     */
    public void replaceLayer(Layer before, Layer after) {
        boolean wasActive = before == activeLayer;

        before.transferMaskAndUITo(after);

        int layerIndex = layerList.indexOf(before);
        assert layerIndex != -1;
        layerList.set(layerIndex, after);

        if (wasActive) {
            activeLayer = after;
        }
    }

    public void setActiveLayer(Layer newActiveLayer) {
        setActiveLayer(newActiveLayer, false, null);
    }

    public void setActiveLayer(Layer newActiveLayer, boolean addToHistory, String editName) {
        if (activeLayer == newActiveLayer) {
            return;
        }
        assert layerList.contains(newActiveLayer)
            : format("new active layer '%s' (%s) not in the layer list of '%s'",
            newActiveLayer.getName(), System.identityHashCode(newActiveLayer), getName());

        Layer oldLayer = activeLayer;
        activeLayer = newActiveLayer;

        if (activeLayer.hasUI()) {
            activeLayer.activateUI();
            Layers.activeLayerChanged(newActiveLayer, false);
        }

        if (addToHistory) {
            History.add(new LayerSelectionChangeEdit(
                editName, this, oldLayer, newActiveLayer));
        }

        if (view != null) {  // shouldn't run while loading the composition
            Tools.editedObjectChanged(activeLayer);
        }

        assert checkInvariant();
    }

    public boolean isActive(Layer layer) {
        return layer == activeLayer;
    }

    public Layer getActiveLayer() {
        return activeLayer;
    }

    public int getActiveLayerIndex() {
        return layerList.indexOf(activeLayer);
    }

    public int getLayerIndex(Layer layer) {
        return layerList.indexOf(layer);
    }

    public Layer getLayer(int i) {
        return layerList.get(i);
    }

    public List<Layer> getLayers() {
        return layerList;
    }

    public int getNumLayers() {
        return layerList.size();
    }

    public int getNumLayers(Predicate<Layer> condition) {
        return (int) layerList.stream()
            .filter(condition)
            .count();
    }

    public int getNumImageLayers() {
        return getNumLayers(layer -> layer instanceof ImageLayer);
    }

    public int getNumTextLayers() {
        return getNumLayers(layer -> layer instanceof TextLayer);
    }

    public void forEachLayer(Consumer<Layer> action) {
        layerList.forEach(action);
    }

    public void forEachContentLayer(Consumer<ContentLayer> action) {
        for (Layer layer : layerList) {
            if (layer instanceof ContentLayer) {
                ContentLayer contentLayer = (ContentLayer) layer;
                action.accept(contentLayer);
            }
        }
    }

    public void forEachDrawable(Consumer<Drawable> action) {
        for (Layer layer : layerList) {
            if (layer instanceof ImageLayer) {
                action.accept((ImageLayer) layer);
            }
            if (layer.hasMask()) {
                action.accept(layer.getMask());
            }
        }
    }

    public void updateAllIconImages() {
        forEachDrawable(Drawable::updateIconImage);
    }

    public boolean activeIsDrawable() {
        if (activeLayer instanceof ImageLayer) {
            return true;
        }
        if (activeLayer.isMaskEditing()) {
            return true;
        }

        return false;
    }

    private Layer getActiveMaskOrLayer() {
        if (activeLayer.isMaskEditing()) {
            return activeLayer.getMask();
        }
        return activeLayer;
    }

    /**
     * Returns the active mask or image layer or null
     */
    public Drawable getActiveDrawable() {
        assert checkInvariant();
        if (activeLayer.isMaskEditing()) {
            return activeLayer.getMask();
        }
        if (activeLayer instanceof ImageLayer) {
            return (ImageLayer) activeLayer;
        }
        return null;
    }

    public Optional<Drawable> getActiveDrawableOpt() {
        return Optional.ofNullable(getActiveDrawable());
    }

    /**
     * Returns the active mask or image layer.
     * Calling this method assumes that the active layer is a Drawable.
     */
    public Drawable getActiveDrawableOrThrow() {
        Drawable dr = getActiveDrawable();
        if (dr == null) {
            throw new IllegalStateException("The active layer is not an image layer or a mask, it is "
                + activeLayer.getClass().getSimpleName());
        }
        return dr;
    }

    public void startMovement(MoveMode mode, boolean duplicateLayer) {
        if (mode.movesTheLayer()) {
            if (duplicateLayer) {
                duplicateActiveLayer();
            }

            Layer layer = getActiveMaskOrLayer();
            layer.startMovement();
        }
        if (mode.movesTheSelection()) {
            if (selection != null) {
                selection.startMovement();
            }
        }
    }

    public void moveActiveContentRelative(MoveMode mode,
                                          double relImX, double relImY) {
        if (mode.movesTheLayer()) {
            Layer layer = getActiveMaskOrLayer();
            layer.moveWhileDragging(relImX, relImY);
        }
        if (mode.movesTheSelection()) {
            if (selection != null) {
                selection.moveWhileDragging(relImX, relImY);
            }
        }
        imageChanged();
    }

    public void endMovement(MoveMode mode) {
        PixelitorEdit layerEdit = null;
        if (mode.movesTheLayer()) {
            Layer layer = getActiveMaskOrLayer();
            // the layer edit will be null if an adjustment
            // layer without a mask was moved.
            layerEdit = layer.endMovement();
        }

        PixelitorEdit selectionEdit = null;
        if (mode.movesTheSelection()) {
            if (selection != null) {
                selectionEdit = selection.endMovement();
            }
        }

        var combinedEdit = MultiEdit.combine(
            layerEdit, selectionEdit, MoveMode.MOVE_BOTH.getEditName());
        if (combinedEdit != null) {
            History.add(combinedEdit);
            imageChanged();
        }
    }

    public void moveActiveLayerUp() {
        assert checkInvariant();

        int oldIndex = layerList.indexOf(activeLayer);
        changeLayerOrder(oldIndex, oldIndex + 1,
            true, LayerMoveAction.RAISE_LAYER);
    }

    public void moveActiveLayerDown() {
        assert checkInvariant();

        int oldIndex = layerList.indexOf(activeLayer);
        changeLayerOrder(oldIndex, oldIndex - 1,
            true, LayerMoveAction.LOWER_LAYER);
    }

    public void moveActiveLayerToTop() {
        assert checkInvariant();

        int oldIndex = layerList.indexOf(activeLayer);
        int newIndex = layerList.size() - 1;
        changeLayerOrder(oldIndex, newIndex,
            true, LayerMoveAction.LAYER_TO_TOP);
    }

    public void moveActiveLayerToBottom() {
        assert checkInvariant();

        int oldIndex = layerList.indexOf(activeLayer);
        changeLayerOrder(oldIndex, 0,
            true, LayerMoveAction.LAYER_TO_BOTTOM);
    }

    public void changeLayerOrder(int oldIndex, int newIndex) {
        changeLayerOrder(oldIndex, newIndex, false, null);
    }

    public void changeLayerOrder(int oldIndex, int newIndex, boolean addToHistory, String editName) {
        if (newIndex < 0) {
            return;
        }
        if (newIndex >= layerList.size()) {
            return;
        }
        if (oldIndex == newIndex) {
            return;
        }

        Layer layer = layerList.get(oldIndex);
        layerList.remove(oldIndex);
        layerList.add(newIndex, layer);

        view.changeLayerButtonOrder(oldIndex, newIndex);
        imageChanged();
        Layers.layerOrderChanged(this);

        if (addToHistory) {
            History.add(new LayerOrderChangeEdit(editName, this, oldIndex, newIndex));
        }
    }

    public void moveLayerSelectionUp() {
        int oldIndex = layerList.indexOf(activeLayer);

        int newIndex = oldIndex + 1;
        if (newIndex >= layerList.size()) {
            return;
        }
        setActiveLayer(layerList.get(newIndex), true, LayerMoveAction.RAISE_LAYER_SELECTION);

        assert ConsistencyChecks.fadeWouldWorkOn(this);
    }

    public void moveLayerSelectionDown() {
        int oldIndex = layerList.indexOf(activeLayer);
        int newIndex = oldIndex - 1;
        if (newIndex < 0) {
            return;
        }

        setActiveLayer(layerList.get(newIndex), true, LayerMoveAction.LOWER_LAYER_SELECTION);

        assert ConsistencyChecks.fadeWouldWorkOn(this);
    }

    public BufferedImage calculateCompositeImage() {
        // TODO why is this not working
//        if(layerList.size() == 1) {
//            Layer firstLayer = layerList.get(0);
//            if(firstLayer instanceof ImageLayer) {
//                ImageLayer layer = (ImageLayer) firstLayer;
//                if(!layer.isBigLayer()) {
//                    return layer.getImage();
//                }
//                return layer.getCanvasSizedSubImage();
//            }
//        }

//        BufferedImage imageSoFar = ImageUtils.createCompatibleImage(getCanvasWidth(), getCanvasHeight());

        var imageSoFar = new BufferedImage(
            canvas.getWidth(), canvas.getHeight(), TYPE_INT_ARGB_PRE);
        Graphics2D g = imageSoFar.createGraphics();

        boolean firstVisibleLayer = true;
        for (Layer layer : layerList) {
            if (layer.isVisible()) {
                BufferedImage result = layer.applyLayer(g, imageSoFar, firstVisibleLayer);
                if (result != null) { // adjustment layer or watermarking text layer
                    imageSoFar = result;
                    if (g != null) {
                        g.dispose();
                    }
                    g = imageSoFar.createGraphics();
                }
                firstVisibleLayer = false;
            }
        }

        g.dispose();

        return imageSoFar;
    }

    public void repaint() {
        view.repaint();
    }

    public void repaintRegion(PPoint start, PPoint end, double thickness) {
        invalidateCompositeCache();
        if (view != null) { // during reload image it can be null
            view.repaintRegion(start, end, thickness);
            view.repaintNavigator(false);
        }
    }

    public void repaintRegion(PRectangle area) {
        invalidateCompositeCache();
        if (view != null) { // during reload image it can be null
            view.repaintRegion(area);
            view.repaintNavigator(false);
        }
    }

    public void dispose() {
        if (selection != null) {
            // stop the timer thread
            selection.die();
        }
    }

    public void paintSelection(Graphics2D g) {
        boolean ruby = false; // feature to be added one day
        if (ruby) {
            paintSelectionAsRubyOverlay(g);
        } else {
            paintSelectionAsMarchingAnts(g);
        }
    }

    private void paintSelectionAsRubyOverlay(Graphics2D g) {
        Shape totalShape = calcTotalSelectionShape();
        if (totalShape != null) {
            Shape inverted = canvas.invertShape(totalShape);
            g.setComposite(AlphaComposite.SrcOver.derive(0.5f));
            g.setColor(Color.RED);
            g.fill(inverted);
        }
    }

    private Shape calcTotalSelectionShape() {
        Shape totalShape = null;
        if (builtSelection != null) {
            totalShape = builtSelection.getShape();
        }
        if (selection != null) {
            if (totalShape == null) {
                totalShape = selection.getShape();
            } else {
                totalShape = ShapeCombination.ADD.combine(
                    totalShape, selection.getShape());
            }
        }
        return totalShape;
    }

    private void paintSelectionAsMarchingAnts(Graphics2D g) {
        if (builtSelection != null) {
            builtSelection.paintMarchingAnts(g);
        }
        if (selection != null) {
            selection.paintMarchingAnts(g);
        }
    }

    public DeselectEdit deselect(boolean addToHistory) {
        if (builtSelection != null) {
            builtSelection.die();
            builtSelection = null;
        }

        DeselectEdit edit = null;
        if (selection != null) {
            edit = createDeselectEdit();
            if (addToHistory && edit != null) {
                History.add(edit);
            }

            boolean wasHidden = selection.isHidden();
            selection.die();
            setSelectionRef(null);

            if (isActive()) {
                if (wasHidden) {
                    SelectionActions.getShowHide().setHideText();
                }
            } else {
                // we can get here from a DeselectEdit.redo on a non-active composition
            }
        }
        if (RunContext.isDevelopment() && isActive()) {
            ConsistencyChecks.selectionActionsEnabledCheck(this);
        }
        return edit;
    }

    private DeselectEdit createDeselectEdit() {
        DeselectEdit edit = null;
        Shape shape = selection.getShape();
        if (shape != null) { // for a simple click without a previous selection this is null
            edit = new DeselectEdit(this, shape);
        }
        return edit;
    }

    public void onSelection(Consumer<Selection> action) {
        if (selection != null) {
            action.accept(selection);
        }
    }

    public Selection getSelection() {
        return selection;
    }

    public Shape getSelectionShape() {
        if (selection != null) {
            return selection.getShape();
        }
        return null;
    }

    public Selection getBuiltSelection() {
        return builtSelection;
    }

    public void setBuiltSelection(Selection selection) {
        builtSelection = selection;
    }

    /**
     * Creates a selection from the given shape and also handles
     * existing selections
     */
    public PixelitorEdit changeSelection(Shape newShape) {
        newShape = clipToCanvasBounds(newShape);
        if (newShape.getBounds().isEmpty()) {
            // the new selection would be outside the canvas
            return null;
        }

        PixelitorEdit edit;
        if (selection != null) {
            int answer = Dialogs.showYesNoCancelDialog("Existing Selection",
                "<html>There is already a selection on " + getName() +
                    ".<br>How do you want to combine new selection with the existing one?",
                new String[]{"Replace", "Add", "Subtract", "Intersect", "Cancel"},
                JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.CLOSED_OPTION || answer == 4) {
                // canceled
                return null;
            }
            Shape oldShape = selection.getShape();
            ShapeCombination interaction = switch (answer) {
                case 0 -> ShapeCombination.REPLACE;
                case 1 -> ShapeCombination.ADD;
                case 2 -> ShapeCombination.SUBTRACT;
                case 3 -> ShapeCombination.INTERSECT;
                default -> throw new IllegalStateException("answer = " + answer);
            };
            selection.setShape(interaction.combine(oldShape, newShape));
            selection.setHidden(false, false);
            edit = new SelectionShapeChangeEdit("Selection Change", this, oldShape);
        } else { // no existing selection
            createSelectionFrom(newShape);
            edit = new NewSelectionEdit(this, selection.getShape());
        }
        return edit;
    }

    /**
     * This should be called only if it can be assumed that there is
     * no existing selection. Otherwise use changeSelection
     */
    public void createSelectionFrom(Shape shape) {
        if (selection != null) {
            throw new IllegalStateException("There is already a selection: " + selection);
        }
        setSelectionRef(new Selection(shape, view));
    }

    /**
     * Changing the selection reference should be done only by using this method
     * (in order to make debugging easier)
     */
    @VisibleForTesting
    public void setSelectionRef(Selection selection) {
        this.selection = selection;
        if (isActive()) {
            SelectionActions.setEnabled(selection != null, this);
            SelectionActions.getShowHide().updateTextFrom(selection);
        }
    }

    public void promoteSelection() {
        assert selection == null || !selection.isAlive() : "selection = " + selection;
        assert builtSelection != null;
        setSelectionRef(builtSelection);
        setBuiltSelection(null);
    }

    public boolean hasSelection() {
        return selection != null;
    }

    @VisibleForTesting
    public boolean hasBuiltSelection() {
        return builtSelection != null;
    }

    /**
     * Returns true if visually there are marching ants,
     * even if the selection is not yet finished
     */
    public boolean showsSelection() {
        return selection != null || builtSelection != null;
    }

    /**
     * Sets the clip area on the given graphics according to the selection.
     * It is assumed that the graphics is relative to the canvas:
     * if it is coming from the image of an ImageLayer, then it must be
     * translated before calling this.
     */
    public void applySelectionClipping(Graphics2D g2) {
        if (selection != null) {
            Shape shape = selection.getShape();
            g2.setClip(shape);
        }
    }

    public void invertSelection() {
        if (selection != null) {
            Shape backupShape = selection.getShape();
            Shape inverted = canvas.invertShape(backupShape);
            if (inverted.getBounds2D().isEmpty()) {
                // presumably everything was selected, and now nothing is
                deselect(true);
            } else {
                selection.setShape(inverted);
                History.add(new SelectionShapeChangeEdit(
                    "Invert Selection", this, backupShape));
            }
        }
    }

    /**
     * Intersects the selection with the given (crop) rectangle.
     * The selection is not translated here into the coordinate
     * system of the new, cropped image.
     */
    public void cropSelection(Rectangle2D cropRect) {
        if (selection != null) {
            Shape currentShape = selection.getShape();
            Shape intersection = ShapeCombination.INTERSECT.combine(currentShape, cropRect);
            if (intersection.getBounds().isEmpty()) {
                selection.die();
                setSelectionRef(null);
            } else {
                selection.setShape(intersection);
            }
        }
    }

    public void createSelectionFromTextLayer() {
        if (activeLayer instanceof TextLayer) {
            TextLayer textLayer = (TextLayer) activeLayer;
            textLayer.createSelectionFromText();
        } else {
            throw new IllegalStateException("active layer is not text layer");
        }
    }

    /**
     * Called when the image-space coordinates have changed (resize, crop, etc.)
     */
    public void imCoordsChanged(AffineTransform at, boolean isUndoRedo) {
        // The selection is explicitly reset to a backup shape
        // when something is undone/redone
        if (selection != null && !isUndoRedo) {
            selection.transform(at);
        }
        // The paths and the tool widgets are transformed even for undo redo.
        // The advantage is simpler code, the disadvantage is that
        // rounding errors could accumulate if the same operation is
        // undone/redone many times
        if (paths != null) {
            paths.imCoordsChanged(at);
        }
        Tools.imCoordsChanged(this, at);
    }

    /**
     * Called when the component-space coordinates have changed,
     * but the pixels remain the same  (zooming, view resizing etc.)
     */
    public void coCoordsChanged() {
        if (guides != null) {
            guides.coCoordsChanged(view);
        }
    }

    /**
     * Returns the (canvas-sized) composite image.
     */
    public BufferedImage getCompositeImage() {
        if (compositeImage == null) {
            compositeImage = calculateCompositeImage();
        }
        return compositeImage;
    }

    public void imageChanged() {
        imageChanged(FULL);
    }

    public void imageChanged(ImageChangeActions actions) {
        imageChanged(actions, false);
    }

    /**
     * The contents of this composition have been changed, the cache is invalidated,
     * and additional actions might be necessary
     */
    public void imageChanged(ImageChangeActions actions, boolean sizeChanged) {
        invalidateCompositeCache();

        if (actions.repaintNeeded()) {
            if (view != null) {
                view.repaint();
                view.repaintNavigator(sizeChanged);
            }
        }

        if (actions.histogramChanged()) {
            HistogramsPanel.updateFrom(this);
        }
    }

    private void invalidateCompositeCache() {
        if (compositeImage != null) {
            compositeImage.flush();
        }
        compositeImage = null;
    }

    public boolean isActive() {
        return OpenImages.activeCompIs(this);
    }

    @VisibleForTesting
    public Rectangle getMaxImageSize() {
        Rectangle max = new Rectangle(0, 0, 0, 0);
        for (Layer layer : layerList) {
            if (layer instanceof ImageLayer) {
                ImageLayer imageLayer = (ImageLayer) layer;
                Rectangle layerBounds = imageLayer.getContentBounds();
                max.add(layerBounds);
            }
        }
        return max;
    }

    /**
     * Useful for testing, but not exposed in the UI, because
     * it can create multiple undo events instead of just one
     */
    @VisibleForTesting
    public void allImageLayersToCanvasSize() {
        for (Layer layer : layerList) {
            if (layer instanceof ImageLayer) {
                ImageLayer imageLayer = (ImageLayer) layer;
                imageLayer.toCanvasSizeWithHistory();
            }
        }
    }

    public void activeLayerToCanvasSize() {
        if (!(activeLayer instanceof ImageLayer)) {
            Messages.showNotImageLayerError(activeLayer);
            return;
        }

        ((ImageLayer) activeLayer).toCanvasSizeWithHistory();
    }

    public void fitCanvasToLayers() {
        EnlargeCanvas enlargeCanvas = new EnlargeCanvas(0, 0, 0, 0);

        for (Layer layer : layerList) {
            if (layer instanceof ContentLayer) {
                ContentLayer contentLayer = (ContentLayer) layer;
                Rectangle contentBounds = contentLayer.getContentBounds();
                enlargeCanvas.ensureCovering(contentBounds, canvas);
            }
        }

        if (enlargeCanvas.doesNothing()) {
            Dialogs.showInfoDialog("Nothing to be done",
                "The canvas is already large enough to show all layer content.");
            return;
        }

        enlargeCanvas.process(this);
    }

    /**
     * The user reordered the layers by dragging
     */
    public void layerReorderingFinished(Layer layer, int newIndex) {
        layerList.remove(layer);
        layerList.add(newIndex, layer);
        imageChanged();
    }

    // called from assertions and unit tests
    @SuppressWarnings("SameReturnValue")
    public boolean checkInvariant() {
        if (layerList.isEmpty()) {
            if (RunContext.isUnitTesting()) {
                return true;
            }
            throw new IllegalStateException("no layer in " + getName());
        }
        if (activeLayer == null) {
            throw new IllegalStateException("no active layer in " + getName());
        }
        if (!layerList.contains(activeLayer)) {
            throw new IllegalStateException(format(
                "active layer (%s) not in list (%s)",
                activeLayer.getName(), layerList));
        }
        return true;
    }

    public int calcNumImages() {
        int count = 0;
        for (Layer layer : layerList) {
            if (layer instanceof ImageLayer) {
                count++;
            }
            if (layer.hasMask()) {
                count++;
            }
        }
        return count;
    }

    public CompletableFuture<Void> saveAsync(SaveSettings saveSettings,
                                             boolean addToRecentMenus) {
        FileFormat format = saveSettings.getFormat();
        File f = saveSettings.getFile();

        if (RunContext.isDevelopment()) {
            System.out.println("Composition::saveAsync: saving " + f.getAbsolutePath());
        }

        Runnable saveTask = format.getSaveTask(this, saveSettings);
        FileFormat.setLastOutput(format);

        return saveAsync(saveTask, f, addToRecentMenus);
    }

    public CompletableFuture<Void> saveAsync(Runnable saveTask,
                                             File file,
                                             boolean addToRecentMenus) {
        assert calledOnEDT() : threadInfo();

        // prevents starting a new save on the EDT while an asynchronous
        // save is already scheduled or running on the IO thread
        String path = file.getAbsolutePath();
        if (IOTasks.isProcessing(path)) {
            return CompletableFuture.completedFuture(null);
        }
        IOTasks.markWriteProcessing(path);

        // set to not dirty already at the beginning of the saving process,
        // so that subsequent closing does not trigger another, parallel save
        boolean wasDirty = isDirty();
        setDirty(false);

        return CompletableFuture
            .runAsync(saveTask, onIOThread)
            .handleAsync((v, e) -> {
                if (e != null) {
                    Messages.showException(e);
                    setDirty(wasDirty);
                } else {
                    afterSuccessfulSaveActions(file, addToRecentMenus);
                }
                IOTasks.writingFinishedFor(path);
                return null;
            }, onEDT);
    }

    private void afterSuccessfulSaveActions(File file, boolean addToRecentMenus) {
        assert calledOnEDT() : threadInfo();

        setFile(file);
        if (addToRecentMenus) {
            RecentFilesMenu.getInstance().addFile(file);
        }
        Messages.showFileSavedMessage(file);
    }

    public Paths getPaths() {
        return paths;
    }

    public Path getActivePath() {
        if (paths != null) {
            return paths.getActivePath();
        }
        return null;
    }

    public boolean hasActivePath() {
        return getActivePath() != null;
    }

    public void setActivePath(Path path) {
        if (path != null && path.getComp() != this) {
            throw new IllegalArgumentException(
                "path belongs to other comp, this = " + toPathDebugString() +
                    ", path.comp = " + path.getComp().toPathDebugString());
        }

        if (paths == null) {
            paths = new Paths();
        }
        paths.setActivePath(path);
    }

    public Guides getGuides() {
        return guides;
    }

    public void setGuides(Guides guides) {
        this.guides = guides;
    }

    public void clearGuides() {
        if (guides == null) {
            return;
        }
        History.add(new GuidesChangeEdit(this, guides, null));
        setGuides(null);
        repaint();
    }

    public void drawGuides(Graphics2D g) {
        if (guides == null) {
            return;
        }
        guides.draw(g);
    }

    public enum ImageChangeActions {
        INVALIDATE_CACHE(false, false) {
        }, REPAINT(true, false) {
        }, HISTOGRAM(false, true) {
        }, FULL(true, true) {
        };

        private final boolean repaint;
        private final boolean updateHistogram;

        ImageChangeActions(boolean repaint, boolean updateHistogram) {
            this.repaint = repaint;
            this.updateHistogram = updateHistogram;
        }

        private boolean repaintNeeded() {
            return repaint;
        }

        private boolean histogramChanged() {
            return updateHistogram;
        }
    }

    public String toPathDebugString() {
        return "Composition{name='" + name + '\''
            + ", active = " + isActive()
            + ", path = " + getActivePath()
            + '}';
    }

    @Override
    public String toString() {
        return "Composition{name='" + name + '\''
            + ", active = " + isActive()
            + ", path = " + getActivePath()
            + ", activeLayer=" + (activeLayer == null ? "null" : activeLayer.getName())
            + ", layerList=" + layerList
            + ", canvas=" + canvas
            + ", selection=" + selection
            + ", dirty=" + dirty
            + '}';
    }

    public static class LayerAdder {
        private final Composition comp;
        private String editName; // null if the add should not be added to history
        private int newLayerIndex = -1;
        private boolean refresh = true;

        public enum Position {TOP, ABOVE_ACTIVE, BELLOW_ACTIVE}

        private Position position = TOP;
        private boolean compInit = false;

        public LayerAdder(Composition comp) {
            this.comp = comp;
        }

        public LayerAdder withHistory(String editName) {
            this.editName = editName;
            return this;
        }

        public LayerAdder atPosition(Position position) {
            this.position = position;
            return this;
        }

        /**
         * Used during composition initialization
         */
        public LayerAdder compInitMode() {
            compInit = true;
            return this;
        }

        /**
         * Used when the composite image does not change
         */
        public LayerAdder noRefresh() {
            refresh = false;
            return this;
        }

        private void calcIndexBasedOnRelPosition() {
            int activeLayerIndex = comp.getActiveLayerIndex();
            if (activeLayerIndex == -1) { // no active layer yet
                assert comp.layerList.isEmpty();
                newLayerIndex = 0;
            } else {
                if (position == BELLOW_ACTIVE) {
                    newLayerIndex = activeLayerIndex;
                } else if (position == ABOVE_ACTIVE) {
                    newLayerIndex = activeLayerIndex + 1;
                } else {
                    throw new IllegalStateException("position = " + position);
                }
            }
        }

        public LayerAdder atIndex(int newLayerIndex) {
            this.newLayerIndex = newLayerIndex;
            return this;
        }

        public void add(Layer newLayer) {
            Layer activeLayerBefore = null;
            MaskViewMode oldViewMode = null;
            if (needsHistory()) {
                activeLayerBefore = comp.activeLayer;
                oldViewMode = comp.view.getMaskViewMode();
            }

            if (newLayerIndex == -1) { // no index was explicitly set
                if (position == TOP) {
                    newLayerIndex = comp.layerList.size();
                } else {
                    calcIndexBasedOnRelPosition();
                }
            }
            comp.layerList.add(newLayerIndex, newLayer);

            if (!compInit) {
                comp.view.addLayerToGUI(newLayer, newLayerIndex);

                // mocked views will not set a UI
                assert RunContext.isUnitTesting() || newLayer.hasUI();
            }

            comp.setActiveLayer(newLayer);
            if (!compInit) {
                comp.setDirty(true);

                if (refresh) {
                    comp.imageChanged();
                }
            }
            if (needsHistory()) {
                History.add(new NewLayerEdit(editName,
                    comp, newLayer, activeLayerBefore, oldViewMode));
            }
            assert comp.checkInvariant();
        }

        private boolean needsHistory() {
            return editName != null;
        }
    }
}
