package org.openstreetmap.josm.plugins.mapillary;

import static org.openstreetmap.josm.tools.I18n.tr;

import org.apache.commons.jcs.access.CacheAccess;
import org.openstreetmap.josm.plugins.mapillary.actions.MapillaryDownloadViewAction;
import org.openstreetmap.josm.plugins.mapillary.cache.MapillaryCache;
import org.openstreetmap.josm.plugins.mapillary.downloads.MapillaryDownloader;
import org.openstreetmap.josm.plugins.mapillary.gui.MapillaryToggleDialog;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.MapView.EditLayerChangeListener;
import org.openstreetmap.josm.gui.MapView.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.AbstractModifiableLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.cache.JCSCacheManager;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.osm.visitor.paint.PaintColors;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent;
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent;
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent;
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataSetListener;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.swing.ImageIcon;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JOptionPane;

import java.util.List;
import java.util.ArrayList;

public class MapillaryLayer extends AbstractModifiableLayer implements
        DataSetListener, EditLayerChangeListener, LayerChangeListener {

    public final static int SEQUENCE_MAX_JUMP_DISTANCE = Main.pref.getInteger(
            "mapillary.sequence-max-jump-distance", 100);

    public static MapillaryLayer INSTANCE;
    public static CacheAccess<String, BufferedImageCacheEntry> CACHE;
    public static MapillaryImage BLUE;
    public static MapillaryImage RED;

    private final MapillaryData mapillaryData = MapillaryData.getInstance();

    public List<Bounds> bounds;


    private MouseAdapter mouseAdapter;

    int highlightPointRadius = Main.pref.getInteger(
            "mappaint.highlight.radius", 7);
    private int highlightStep = Main.pref.getInteger("mappaint.highlight.step",
            4);

    public MapillaryLayer() {
        super(tr("Mapillary Images"));
        bounds = new ArrayList<>();
        init();
    }

    /**
     * Initializes the Layer.
     */
    private void init() {
        MapillaryLayer.INSTANCE = this;
        startMouseAdapter();
        try {
            CACHE = JCSCacheManager.getCache("Mapillary");
        } catch (IOException e) {
            Main.error(e);
        }
        if (Main.map != null && Main.map.mapView != null) {
            Main.map.mapView.addMouseListener(mouseAdapter);
            Main.map.mapView.addMouseMotionListener(mouseAdapter);
            Main.map.mapView.addLayer(this);
            MapView.addEditLayerChangeListener(this, false);
            MapView.addLayerChangeListener(this);
            Main.map.mapView.getEditLayer().data.addDataSetListener(this);
        }
        MapillaryPlugin.setMenuEnabled(MapillaryPlugin.EXPORT_MENU, true);
        MapillaryPlugin.setMenuEnabled(MapillaryPlugin.SIGN_MENU, true);
        Main.map.mapView.setActiveLayer(this);
        Main.map.repaint();
    }

    public void startMouseAdapter() {
        mouseAdapter = new MapillaryMouseAdapter();
    }

    public synchronized static MapillaryLayer getInstance() {
        if (MapillaryLayer.INSTANCE == null)
            MapillaryLayer.INSTANCE = new MapillaryLayer();
        return MapillaryLayer.INSTANCE;
    }

    /**
     * Downloads all images of the area covered by the OSM data. This is only
     * just for automatic download.
     */
    public void download() {
        checkBigAreas();
        if (Main.pref.getBoolean("mapillary.download-manually"))
            return;
        for (Bounds bounds : Main.map.mapView.getEditLayer().data
                .getDataSourceBounds()) {
            if (!this.bounds.contains(bounds)) {
                this.bounds.add(bounds);
                new MapillaryDownloader().getImages(bounds.getMin(),
                        bounds.getMax());
            }
        }
    }

    /**
     * Checks if the area of the OSM data is too big. This means that probably
     * lots of Mapillary images are going to be downloaded, slowing down the
     * program too much. To solve this the automatic is stopped, an alert is
     * shown and you will have to download areas manually.
     */
    private void checkBigAreas() {
        double area = 0;
        for (Bounds bounds : Main.map.mapView.getEditLayer().data
                .getDataSourceBounds()) {
            area += bounds.getArea();
        }
        if (area > MapillaryDownloadViewAction.MAX_AREA) {
            Main.pref.put("mapillary.download-manually", true);
            JOptionPane
                    .showMessageDialog(
                            Main.parent,
                            tr("The downloaded OSM area is too big. Download mode has been change to manual. You can change this back to automatic in preferences settings."));
        }
    }

    /**
     * Returns the MapillaryData object, which acts as the database of the
     * Layer.
     * 
     * @return
     */
    public MapillaryData getMapillaryData() {
        return mapillaryData;
    }

    /**
     * Method invoked when the layer is destroyed.
     */
    @Override
    public void destroy() {
        MapillaryToggleDialog.getInstance().mapillaryImageDisplay
                .setImage(null);
        MapillaryData.getInstance().getImages().clear();
        MapillaryLayer.INSTANCE = null;
        MapillaryData.INSTANCE = null;
        MapillaryPlugin.setMenuEnabled(MapillaryPlugin.EXPORT_MENU, false);
        MapillaryPlugin.setMenuEnabled(MapillaryPlugin.SIGN_MENU, false);
        MapillaryPlugin.setMenuEnabled(MapillaryPlugin.ZOOM_MENU, false);
        Main.map.mapView.removeMouseListener(mouseAdapter);
        Main.map.mapView.removeMouseMotionListener(mouseAdapter);
        MapView.removeEditLayerChangeListener(this);
        if (Main.map.mapView.getEditLayer() != null)
            Main.map.mapView.getEditLayer().data.removeDataSetListener(this);
        super.destroy();
    }

    /**
     * Returns true any of the images from the database has been modified.
     */
    @Override
    public boolean isModified() {
        for (MapillaryAbstractImage image : mapillaryData.getImages())
            if (image.isModified())
                return true;
        return false;
    }

    /**
     * Paints the database in the map.
     */
    @Override
    public void paint(Graphics2D g, MapView mv, Bounds box) {
        synchronized (this) {
            // Draw colored lines
            MapillaryLayer.BLUE = null;
            MapillaryLayer.RED = null;
            MapillaryToggleDialog.getInstance().blueButton.setEnabled(false);
            MapillaryToggleDialog.getInstance().redButton.setEnabled(false);
            
            // Sets blue and red lines and enables/disables the buttons
            if (mapillaryData.getSelectedImage() != null) {
                MapillaryImage[] closestImages = getClosestImagesFromDifferentSequences();
                Point selected = mv.getPoint(mapillaryData.getSelectedImage()
                        .getLatLon());
                if (closestImages[0] != null) {
                    MapillaryLayer.BLUE = closestImages[0];
                    g.setColor(Color.BLUE);
                    g.drawLine(mv.getPoint(closestImages[0].getLatLon()).x,
                            mv.getPoint(closestImages[0].getLatLon()).y,
                            selected.x, selected.y);
                    MapillaryToggleDialog.getInstance().blueButton
                            .setEnabled(true);
                }
                if (closestImages[1] != null) {
                    MapillaryLayer.RED = closestImages[1];
                    g.setColor(Color.RED);
                    g.drawLine(mv.getPoint(closestImages[1].getLatLon()).x,
                            mv.getPoint(closestImages[1].getLatLon()).y,
                            selected.x, selected.y);
                    MapillaryToggleDialog.getInstance().redButton
                            .setEnabled(true);
                }
            }
            g.setColor(Color.WHITE);
            for (MapillaryAbstractImage imageAbs : mapillaryData.getImages()) {
                if (!imageAbs.isVisible())
                    continue;
                Point p = mv.getPoint(imageAbs.getLatLon());
                if (imageAbs instanceof MapillaryImage) {
                    MapillaryImage image = (MapillaryImage) imageAbs;
                    Point nextp;
                    // Draw sequence line
                    if (image.getSequence() != null
                            && image.next() != null && image.next().isVisible()) {
                        nextp = mv.getPoint(image.getSequence().next(image)
                                .getLatLon());
                        g.drawLine(p.x, p.y, nextp.x, nextp.y);
                    }
                    
                    ImageIcon icon;
                    if (!mapillaryData.getMultiSelectedImages().contains(image))
                        icon = MapillaryPlugin.MAP_ICON;
                    else
                        icon = MapillaryPlugin.MAP_ICON_SELECTED;
                    draw(g, image, icon, p);
                    if (!image.getSigns().isEmpty()) {
                        g.drawImage(MapillaryPlugin.MAP_SIGN.getImage(), p.x
                                + icon.getIconWidth() / 2,
                                p.y - icon.getIconHeight() / 2,
                                Main.map.mapView);
                    }
                } else if (imageAbs instanceof MapillaryImportedImage) {
                    MapillaryImportedImage image = (MapillaryImportedImage) imageAbs;
                    ImageIcon icon;
                    if (!mapillaryData.getMultiSelectedImages().contains(image))
                        icon = MapillaryPlugin.MAP_ICON_IMPORTED;
                    else
                        icon = MapillaryPlugin.MAP_ICON_SELECTED;
                    draw(g, image, icon, p);
                }
            }
        }
    }

    /**
     * Draws the highlight of the icon.
     * 
     * @param g
     * @param p
     * @param size
     */
    private void drawPointHighlight(Graphics2D g, Point p, int size) {
        Color oldColor = g.getColor();
        Color highlightColor = PaintColors.HIGHLIGHT.get();
        Color highlightColorTransparent = new Color(highlightColor.getRed(),
                highlightColor.getGreen(), highlightColor.getBlue(), 100);
        g.setColor(highlightColorTransparent);
        int s = size + highlightPointRadius;
        while (s >= size) {
            int r = (int) Math.floor(s / 2d);
            g.fillRoundRect(p.x - r, p.y - r, s, s, r, r);
            s -= highlightStep;
        }
        g.setColor(oldColor);
    }

    /**
     * Draws the given icon of an image. Also checks if the mouse is over the
     * image.
     * 
     * @param g
     * @param image
     * @param icon
     * @param p
     */
    private void draw(Graphics2D g, MapillaryAbstractImage image,
            ImageIcon icon, Point p) {
        Image imagetemp = icon.getImage();
        BufferedImage bi = (BufferedImage) imagetemp;
        int width = icon.getIconWidth();
        int height = icon.getIconHeight();

        // Rotate the image
        double rotationRequired = Math.toRadians(image.getCa());
        double locationX = width / 2;
        double locationY = height / 2;
        AffineTransform tx = AffineTransform.getRotateInstance(
                rotationRequired, locationX, locationY);
        AffineTransformOp op = new AffineTransformOp(tx,
                AffineTransformOp.TYPE_BILINEAR);

        g.drawImage(op.filter(bi, null), p.x - (width / 2), p.y - (height / 2),
                Main.map.mapView);
        if (MapillaryData.getInstance().getHoveredImage() == image) {
            drawPointHighlight(g, p, 16);
        }
    }

    @Override
    public Icon getIcon() {
        return MapillaryPlugin.ICON16;
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void mergeFrom(Layer from) {
        throw new UnsupportedOperationException(
                "This layer does not support merging yet");
    }

    @Override
    public Action[] getMenuEntries() {
        List<Action> actions = new ArrayList<>();
        actions.add(LayerListDialog.getInstance().createShowHideLayerAction());
        actions.add(LayerListDialog.getInstance().createDeleteLayerAction());
        actions.add(new LayerListPopup.InfoAction(this));
        return actions.toArray(new Action[actions.size()]);
    }

    /**
     * Returns the 2 closest images belonging to a different sequence.
     * 
     * @return
     */
    private MapillaryImage[] getClosestImagesFromDifferentSequences() {
        if (!(mapillaryData.getSelectedImage() instanceof MapillaryImage))
            return new MapillaryImage[2];
        MapillaryImage selected = (MapillaryImage) mapillaryData
                .getSelectedImage();
        MapillaryImage[] ret = new MapillaryImage[2];
        double[] distances = { SEQUENCE_MAX_JUMP_DISTANCE,
                SEQUENCE_MAX_JUMP_DISTANCE };
        LatLon selectedCoords = mapillaryData.getSelectedImage().getLatLon();
        for (MapillaryAbstractImage imagePrev : mapillaryData.getImages()) {
            if (!(imagePrev instanceof MapillaryImage))
                continue;
            if (!imagePrev.isVisible())
                continue;
            MapillaryImage image = (MapillaryImage) imagePrev;
            if (image.getLatLon().greatCircleDistance(selectedCoords) < SEQUENCE_MAX_JUMP_DISTANCE
                    && selected.getSequence() != image.getSequence()) {
                if ((ret[0] == null && ret[1] == null)
                        || (image.getLatLon().greatCircleDistance(
                                selectedCoords) < distances[0] && (ret[1] == null || image
                                .getSequence() != ret[1].getSequence()))) {
                    ret[0] = image;
                    distances[0] = image.getLatLon().greatCircleDistance(
                            selectedCoords);
                } else if ((ret[1] == null || image.getLatLon()
                        .greatCircleDistance(selectedCoords) < distances[1])
                        && image.getSequence() != ret[0].getSequence()) {
                    ret[1] = image;
                    distances[1] = image.getLatLon().greatCircleDistance(
                            selectedCoords);
                }
            }
        }
        // Predownloads the thumbnails
        if (ret[0] != null)
            new MapillaryCache(ret[0].getKey(), MapillaryCache.Type.THUMBNAIL)
                    .submit(MapillaryData.getInstance(), false);
        if (ret[1] != null)
            new MapillaryCache(ret[1].getKey(), MapillaryCache.Type.THUMBNAIL)
                    .submit(MapillaryData.getInstance(), false);
        return ret;
    }

    @Override
    public Object getInfoComponent() {
        StringBuilder sb = new StringBuilder();
        sb.append(tr("Mapillary layer"));
        sb.append("\n");
        sb.append(tr("Total images:"));
        sb.append(" ");
        sb.append(this.size());
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public String getToolTipText() {
        return this.size() + " " + tr("images");
    }

    private int size() {
        return mapillaryData.getImages().size();
    }

    // EditDataLayerChanged
    @Override
    public void editLayerChanged(OsmDataLayer oldLayer, OsmDataLayer newLayer) {
    }

    /**
     * When more data is downloaded, a delayed update is thrown, in order to
     * wait for the data bounds to be set.
     * 
     * @param event
     */
    @Override
    public void dataChanged(DataChangedEvent event) {
        Main.worker.submit(new delayedDownload());
    }

    private class delayedDownload extends Thread {

        @Override
        public void run() {
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                Main.error(e);
            }
            MapillaryLayer.getInstance().download();
        }
    }

    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
    }

    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
    }

    @Override
    public void tagsChanged(TagsChangedEvent event) {
    }

    @Override
    public void nodeMoved(NodeMovedEvent event) {
    }

    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
    }

    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
    }

    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
    }

    @Override
    public void activeLayerChange(Layer oldLayer, Layer newLayer) {
        if (newLayer == this) {
            if (MapillaryData.getInstance().getImages().size() > 0)
                Main.map.statusLine.setHelpText(tr("Total images: {0}",
                        MapillaryData.getInstance().getImages().size()));
            else
                Main.map.statusLine.setHelpText(tr("No images found"));
        }
    }

    @Override
    public void layerAdded(Layer newLayer) {
    }

    @Override
    public void layerRemoved(Layer oldLayer) {
    }
}
