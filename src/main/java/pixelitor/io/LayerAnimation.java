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

package pixelitor.io;

import pd.AnimatedGifEncoder;
import pixelitor.Composition;
import pixelitor.gui.utils.GUIUtils;
import pixelitor.layers.AdjustmentLayer;
import pixelitor.layers.Layer;
import pixelitor.utils.ImageUtils;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A layer animation (an animation based on the layers of a composition)
 */
public class LayerAnimation {
    private final int delayMillis;
    private final List<BufferedImage> images = new ArrayList<>();

    public LayerAnimation(Composition comp, int delayMillis, boolean pingPong) {
        this.delayMillis = delayMillis;
        addComposition(comp, pingPong);
    }

    private void addComposition(Composition comp, boolean pingPong) {
        int numLayers = comp.getNumLayers();
        for (int i = 0; i < numLayers; i++) {
            addLayerToAnimation(comp, i);
        }
        if (pingPong && numLayers > 2) {
            for (int i = numLayers - 2; i > 0; i--) {
                addLayerToAnimation(comp, i);
            }
        }
    }

    private void addLayerToAnimation(Composition comp, int layerIndex) {
        Layer layer = comp.getLayer(layerIndex);
        if (layer instanceof AdjustmentLayer) {
            return;
        }
        BufferedImage image = ImageUtils.createSysCompatibleImage(comp.getCanvas());
        Graphics2D g = image.createGraphics();

        // using this takes care of masks, translations
        BufferedImage returned = layer.applyLayer(g, image, true);

        g.dispose();
        if (returned != null) {
            images.add(returned);
        } else {
            images.add(image);
        }
    }

    private void export(File f) {
        AnimatedGifEncoder e = new AnimatedGifEncoder();
        e.start(f);
        e.setDelay(delayMillis);
        e.setRepeat(0);
        images.forEach(e::addFrame);
        e.finish();
    }

    public void saveToFile(File selectedFile) {
        assert selectedFile != null;

        Runnable r = () -> export(selectedFile);
        GUIUtils.runWithBusyCursor(r);
    }
}
