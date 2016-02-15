package org.activehome.experimenter;

/*
 * #%L
 * Active Home :: Experimenter
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 Active Home Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.activehome.com.Request;
import org.activehome.com.RequestCallback;
import org.activehome.com.error.Error;
import org.activehome.com.error.ErrorType;
import org.activehome.context.data.ComponentProperties;
import org.activehome.context.data.UserInfo;
import org.activehome.tools.file.FileHelper;

import java.io.File;
import java.util.Date;
import java.util.LinkedList;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class XpSetup {

    private Experimenter exper;
    private String urlSQLSource;
    private String xpFile;
    private String resultFolder;
    private LinkedList<ComponentProperties> componentToInstall;

    public XpSetup(final Experimenter experimenter,
                   final String urlSQLSource,
                   final String xpFile) {
        exper = experimenter;
        this.xpFile = xpFile;
        this.urlSQLSource = urlSQLSource;
        componentToInstall = new LinkedList<>();
        initFolder();
    }

    private boolean initFolder() {
        resultFolder = System.getProperty("active-home.home") + "/results_" + System.currentTimeMillis();
        File file = new File(resultFolder);
        return file.exists() || file.mkdirs();
    }

    LinkedList<XP> loadXPProperties() {
        LinkedList<XP> xpList = new LinkedList<>();
        JsonObject jsonXpdetails = JsonObject.readFrom(FileHelper.fileToString(
                new File(System.getProperty("active-home.home") + "/" + xpFile)));
        JsonObject sources = jsonXpdetails.get("sources").asObject();
        for (String src : sources.names()) {
            for (JsonValue period : jsonXpdetails.get("sources").asObject().get(src).asArray()) {
                JsonObject jsonXp = period.asObject();
                for (String key : jsonXpdetails.names()) {
                    if (!key.equals("sources")) {
                        jsonXp.add(key, jsonXpdetails.get(key));
                    }
                }
                jsonXp.add("source", src);
                xpList.add(new XP(exper, jsonXp.asObject()));
            }
        }
        return xpList;
    }


    private void applyChanges(final JsonObject properties,
                              final UserInfo userInfo,
                              final RequestCallback callback) {
        removeAllXPComponents(userInfo, new RequestCallback() {
            public void success(final Object result) {
                installXpComponents(userInfo, new RequestCallback() {
                    public void success(final Object result) {
                        setupUser(properties, userInfo, callback);
                    }

                    public void error(final Error result) {
                        callback.error(result);
                    }
                });
            }

            public void error(final Error result) {
                callback.error(result);
            }
        });
    }

    public void removeAllXPComponents(final UserInfo userInfo,
                                      final RequestCallback callback) {
        Request request = new Request(exper.getFullId(), exper.getNode() + ".linker", exper.getCurrentTime(),
                "stopComponentByType", new Object[]{new String[]{"IO", "Predictor", "BruteForceScheduler",
                "Evaluator", "Planner", "PowerBalancer"}});
        exper.sendRequest(request, new RequestCallback() {
            public void success(final Object result) {
                installXpComponents(userInfo, callback);
            }

            public void error(final Error result) {
                callback.error(result);
            }
        });
    }


    /**
     * Send a 'startComponent' request to the linker for each appliance
     *
     * @param userInfo the user details
     * @param callback forward install result (boolean)
     */
    public void installXpComponents(final UserInfo userInfo,
                                    final RequestCallback callback) {
        if (componentToInstall.size() > 0) {
            Request request = new Request(exper.getFullId(), exper.getNode() + ".linker", exper.getCurrentTime(),
                    "startComponent", new Object[]{componentToInstall.poll(), userInfo});
            exper.sendRequest(request, new RequestCallback() {
                public void success(final Object result) {
                    installXpComponents(userInfo, callback);
                }

                public void error(final Error result) {
                    callback.error(result);
                }
            });
        } else {
            callback.success(true);
        }
    }

    public void setupUser(final RequestCallback callback) {
        UserInfo userInfo = exper.xpUser();
        ComponentProperties cp = new ComponentProperties(userInfo.getUserType(),
                userInfo.getId(), new JsonObject(), new String[]{exper.getNode()});
        Request req = new Request(exper.getFullId(), exper.getNode() + ".linker",
                exper.getCurrentTime(), "startComponent", new Object[]{cp, userInfo});
        exper.sendRequest(req, new RequestCallback() {
            public void success(Object result) {
                callback.success(true);
            }

            public void error(Error result) {
                callback.error(new Error(ErrorType.START_ERROR,
                        "Unable to start the manager for this user."));
            }
        });
    }

    /**
     * Removes all loaded component and install from new ones
     *
     * @param properties contains an json array 'appToStart' with appliance properties (ComponentProperties)
     * @param userInfo   the user details
     * @param callback   forward remove/install result (boolean)
     */
    public void setProperties(final JsonObject properties,
                              final UserInfo userInfo,
                              final RequestCallback callback) {
        componentToInstall = new LinkedList<>();

        JsonObject setting = exper.getCtrl().getCurrentXP().getCurrentSetting();
        boolean predictive = setting.get("predictive") != null && setting.get("predictive").asBoolean();
        boolean reactive = setting.get("reactive") != null && setting.get("reactive").asBoolean();
        boolean collab = setting.get("collab") != null && setting.get("collab").asBoolean();

        if (predictive) {
            componentToInstall.addAll(setupPredictive(properties, collab));
        }
        if (reactive) {
            componentToInstall.addAll(setupReactive(properties, collab));
        }

        componentToInstall.addAll(setupAppliances(properties));
        componentToInstall.addAll(setupEvaluators(properties));
        componentToInstall.addAll(setupGridAndFM(properties));

        applyChanges(properties, userInfo, callback);
    }

    private LinkedList<ComponentProperties> setupPredictive(final JsonObject properties,
                                                            final boolean collab) {
        LinkedList<ComponentProperties> compPropList = new LinkedList<>();
        JsonObject attr = new JsonObject();
        compPropList.add(new ComponentProperties("org.activehome.energy.scheduler.bruteforce.BruteForceScheduler",
                "scheduler", attr, new String[]{}));

        compPropList.add(new ComponentProperties("org.activehome.energy.planner.Planner",
                "planner", attr, new String[]{}));

        if (properties.get("predictors") != null) {
            for (JsonValue predictor : properties.get("predictors").asArray()) {
                String type = predictor.asString();
                String typeNoVersion = type.split("/")[0];
                JsonObject predictorAttr = new JsonObject();
                if (typeNoVersion.endsWith("EApplianceUsagePredictor")) {
                    predictorAttr.add("urlSQLSource", urlSQLSource);
                    predictorAttr.add("tableName", properties.get("source").asString());
                }
                String name = typeNoVersion.substring(typeNoVersion.lastIndexOf(".") + 1);
                compPropList.add(new ComponentProperties(type, name, predictorAttr, new String[]{}));
            }
        }

        return compPropList;
    }

    private LinkedList<ComponentProperties> setupReactive(final JsonObject properties,
                                                          final boolean collab) {
        LinkedList<ComponentProperties> compPropList = new LinkedList<>();
        JsonObject attr = new JsonObject();
        compPropList.add(new ComponentProperties("org.activehome.energy.balancer.PowerBalancer",
                "balancer", attr, new String[]{}));
        return compPropList;
    }

    private LinkedList<ComponentProperties> setupEvaluators(final JsonObject properties) {
        LinkedList<ComponentProperties> compPropList = new LinkedList<>();
        if (properties.get("evaluators") != null) {
            for (JsonValue evaluator : properties.get("evaluators").asArray()) {
                String type = evaluator.asString();
                String typeNoVersion = type.split("/")[0];
                JsonObject evaluatorAttr = new JsonObject();
                evaluatorAttr.add("bindingXp", "pushReport>Experimenter.getReport");
                String name = typeNoVersion.substring(typeNoVersion.lastIndexOf(".") + 1);
                compPropList.add(new ComponentProperties(type, name, evaluatorAttr, new String[]{}));
            }
        }
        return compPropList;
    }

    private LinkedList<ComponentProperties> setupAppliances(final JsonObject properties) {
        LinkedList<ComponentProperties> compPropList = new LinkedList<>();
        if (properties.get("appToStart") != null && properties.get("appToStart").isArray()) {
            JsonArray appArray = properties.get("appToStart").asArray();
            for (int i = 0; i < appArray.size(); i++) {
                ComponentProperties cp = new ComponentProperties(appArray.get(i).asObject());
                if (cp.getAttributeMap().containsKey("urlSQLSource")) {
                    cp.getAttributeMap().put("urlSQLSource", urlSQLSource);
                }
                compPropList.add(cp);
            }
            JsonObject attr = new JsonObject();
            attr.add("urlSQLSource", urlSQLSource);
            attr.add("tableName", properties.get("source").asString());
            ComponentProperties cpBaseLoad = new ComponentProperties(
                    "org.activehome.energy.io.emulator.EBaseLoad/0.0.3-SNAPSHOT", "baseload", attr, new String[]{});
            compPropList.add(cpBaseLoad);
        }
        return compPropList;
    }

    private LinkedList<ComponentProperties> setupGridAndFM(final JsonObject properties) {
        LinkedList<ComponentProperties> compPropList = new LinkedList<>();
        if (properties.get("fiscalmeters") != null) {
            for (JsonValue fmJson : properties.get("fiscalmeters").asArray()) {
                String type = fmJson.asObject().get("type").asString();
                String name = fmJson.asObject().get("name").asString();
                JsonObject fmAttr = new JsonObject();
                fmJson.asObject().names().stream()
                        .filter(key -> !key.equals("type") && !key.equals("name"))
                        .forEach(key -> {
                            fmAttr.add(key, fmJson.asObject().get(key));
                        });

                compPropList.add(new ComponentProperties(type, name, fmAttr, new String[]{}));
            }
        }
        if (properties.get("grid") != null) {
            compPropList.add(new ComponentProperties("org.activehome.energy.io.emulator.EGrid",
                    "grid", properties.get("grid").asObject(), new String[]{}));
        }
        return compPropList;
    }

    private void setupUser(final JsonObject properties,
                           final UserInfo userInfo,
                           final RequestCallback callback) {
        if (properties.get("source") != null) {
            Request request = new Request(exper.getFullId(), exper.getNode() + ".linker", exper.getCurrentTime(),
                    "updateComponentAttribute", new Object[]{userInfo.getId(),
                    "tableName", properties.get("source").asString()});
            exper.sendRequest(request, new RequestCallback() {
                public void success(final Object result) {
                    Request request = new Request(exper.getFullId(), exper.getNode() + ".linker", exper.getCurrentTime(),
                            "updateComponentAttribute", new Object[]{userInfo.getId(),
                            "urlSQLSource", urlSQLSource});
                    exper.sendRequest(request, new RequestCallback() {
                        public void success(final Object result) {
                            installXpComponents(userInfo, callback);
                        }

                        public void error(final Error result) {
                            callback.error(result);
                        }
                    });
                }

                public void error(final Error result) {
                    callback.error(result);
                }
            });
        } else {
            callback.success(new Error(ErrorType.METHOD_ERROR, "Data source missing."));
        }
    }

    public String getResultFolder() {
        return resultFolder;
    }

}
