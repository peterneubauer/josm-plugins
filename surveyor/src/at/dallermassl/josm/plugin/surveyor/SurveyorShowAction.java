/**
 * Copyright by Christof Dallermassl
 * This program is free software and licensed under GPL.
 */
package at.dallermassl.josm.plugin.surveyor;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;

import livegps.LiveGpsPlugin;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.XmlObjectParser;
import org.xml.sax.SAXException;

import at.dallermassl.josm.plugin.surveyor.util.ResourceLoader;

/**
 * @author cdaller
 *
 */
public class SurveyorShowAction extends JosmAction {
    private static final long serialVersionUID = 2184570223633094734L;
    private static final String DEFAULT_SOURCE = "resource://resources/surveyor.xml";
    private JFrame surveyorFrame;
    private LiveGpsPlugin gpsPlugin;

    public SurveyorShowAction(LiveGpsPlugin gpsPlugin) {
        super(tr("Surveyor..."), "surveyormenu.png", tr("Open surveyor tool."),
        Shortcut.registerShortcut("surveyor:open", tr("Tool: {0}", tr("Surveyor...")),
        KeyEvent.VK_R, Shortcut.CTRL_SHIFT), true);
        this.gpsPlugin = gpsPlugin;
    }

    @Override
	public void actionPerformed(ActionEvent e) {
        if(surveyorFrame == null) {
            surveyorFrame = new JFrame();

            SurveyorComponent comp = createComponent();

            // add component as gps event listener:
            gpsPlugin.addPropertyChangeListener(comp);

            // add some hotkeys to the component:
            ActionMap actionMap = comp.getActionMap();
            InputMap inputMap = comp.getInputMap();
            // zoomout:
            actionMap.put("zoomout", new AbstractAction() {
                @Override
				public void actionPerformed(ActionEvent e) {
                    if(Main.map != null && Main.map.mapView != null) {
                        Main.map.mapView.zoomToFactor(2);
                    }
                }
            });
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "zoomout");
            // zoomin:
            actionMap.put("zoomin", new AbstractAction() {
                @Override
				public void actionPerformed(ActionEvent e) {
                    if(Main.map != null && Main.map.mapView != null) {
                        Main.map.mapView.zoomToFactor(1/2);
                    }
                }
            });
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "zoomin");
            // autocenter:
            actionMap.put("autocenter", new AbstractAction() {
                @Override
				public void actionPerformed(ActionEvent e) {
                    // toggle autocenter
                    gpsPlugin.setAutoCenter(!gpsPlugin.isAutoCenter());
                }
            });
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "autocenter");

            surveyorFrame.add(comp);
            surveyorFrame.pack();
            surveyorFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            //surveyorFrame.setTitle((String)getValue(AbstractAction.NAME));
            surveyorFrame.setTitle(tr("Surveyor"));
            // <FIXXME date="28.04.2007" author="cdaller">
            // TODO get old pos of frame from properties
            // </FIXXME>
            SurveyorPlugin.setSurveyorFrame(surveyorFrame);
        }
        surveyorFrame.setAlwaysOnTop(true);
        surveyorFrame.setVisible(true);

    }

    public SurveyorComponent createComponent() {
        String source = Main.pref.get("surveyor.source");
        if(source == null || source.length() == 0) {
            source = DEFAULT_SOURCE;
            Main.pref.put("surveyor.source", DEFAULT_SOURCE);
            // <FIXXME date="04.05.2007" author="cdaller">
            // TODO copy xml file to .josm directory if it does not exist!
            // </FIXXME>
        }
        try (InputStream in = ResourceLoader.getInputStream(source)) {
            return createComponent(in);
        } catch (IOException e) {
            Main.error(e);
            JOptionPane.showMessageDialog(Main.parent, tr("Could not read surveyor definition: {0}",source));
        } catch (SAXException e) {
            Main.error(e);
            JOptionPane.showMessageDialog(Main.parent, tr("Error parsing {0}: {1}", source, e.getMessage()));
        }
        return null;
    }

    /**
     * Parse an xml file containing the definitions for the surveyor component.
     * @param in the inputstream to read the xml from.
     * @return the component.
     * @throws SAXException if the xml could not be read.
     */
    public SurveyorComponent createComponent(InputStream in) throws SAXException {
        XmlObjectParser parser = new XmlObjectParser();
        parser.mapOnStart("surveyor", SurveyorComponent.class);
        parser.map("button", ButtonDescription.class);
        parser.map("action", SurveyorActionDescription.class);

        SurveyorComponent surveyorComponent = null;
        parser.start(new BufferedReader(new InputStreamReader(in)));
        List<SurveyorActionDescription> actions = new ArrayList<>();
        while(parser.hasNext()) {
            Object object = parser.next();
            if (object instanceof SurveyorComponent) {
                //System.out.println("SurveyorComponent " + object);
                surveyorComponent = (SurveyorComponent) object;
            } else if (object instanceof ButtonDescription) {
                //System.out.println("ButtonDescription " + object);
                ((ButtonDescription)object).setActions(actions);
                surveyorComponent.addButton(((ButtonDescription)object));
                actions = new ArrayList<>();
            } else if (object instanceof SurveyorActionDescription) {
                //System.out.println("SurveyorActionDescription " + object);
                actions.add((SurveyorActionDescription)object);
            } else {
                Main.error("surveyor: unknown xml element: " + object);
            }
        }
        return surveyorComponent;
    }
}
