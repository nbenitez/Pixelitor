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
package pixelitor.menus;

import pixelitor.menus.edit.RedoMenuItem;
import pixelitor.menus.edit.UndoMenuItem;

import javax.swing.*;

/**
 * When to enable a menu or a menu item
 */
enum EnabledIf {
    THERE_IS_OPEN_IMAGE {
        @Override
        public JMenuItem createMenuItem(Action a) {
            return new OpenImageAwareMenuItem(a);
        }
    }, UNDO_POSSIBLE {
        @Override
        public JMenuItem createMenuItem(Action a) {
            return new UndoMenuItem(a);
        }
    }, REDO_POSSIBLE {
        @Override
        public JMenuItem createMenuItem(Action a) {
            return new RedoMenuItem(a);
        }
    },
    /**
     * The enabled state is controlled by the Action - in most cases
     * this means "always enabled"
     */
    ACTION_ENABLED {
        @Override
        public JMenuItem createMenuItem(Action a) {
            return new JMenuItem(a);
        }
    };

    public abstract JMenuItem createMenuItem(Action a);
}
