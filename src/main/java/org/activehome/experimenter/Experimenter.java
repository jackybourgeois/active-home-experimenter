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
import org.activehome.com.*;
import org.activehome.com.error.Error;
import org.activehome.com.error.ErrorType;
import org.activehome.context.data.ComponentProperties;
import org.activehome.context.data.MetricRecord;
import org.activehome.context.data.Schedule;
import org.activehome.context.helper.ModelHelper;
import org.activehome.mysql.HelperMySQL;
import org.activehome.service.Service;
import org.activehome.service.RequestHandler;
import org.activehome.tools.file.FileHelper;
import org.activehome.context.data.UserInfo;
import org.kevoree.annotation.*;
import org.kevoree.api.KevScriptService;
import org.kevoree.api.ModelService;
import org.kevoree.Package;
import org.kevoree.api.BootstrapService;
import org.kevoree.ContainerRoot;
import org.kevoree.api.handler.UUIDModel;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.factory.KevoreeFactory;
import org.kevoree.log.Log;
import org.kevoree.pmodeling.api.ModelCloner;
import org.kevoree.ContainerNode;
import org.kevoree.TypeDefinition;
import org.kevoree.ComponentInstance;
import org.kevoree.ComponentInstance;
import org.kevoree.DictionaryType;
import org.kevoree.DictionaryAttribute;


import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class Experimenter extends Service {

    @Param(defaultValue = "An experimenter to play with data in multiple settings.")
    private String description;
    @Param(defaultValue = "/active-home-experimenter")
    private String src;

    /**
     * Source of the data.
     */
    @Param(optional = false)
    private String urlSQLSource;
    /**
     * look for xp in the given file and run them automatically
     */
    @Param(defaultValue = "")
    private String xpFile;

    private ModelCloner cloner;

    @KevoreeInject
    private KevScriptService kevScriptService;
    @KevoreeInject
    private ModelService modelService;

    @Output
    protected org.kevoree.api.Port toSchedule;

    private LinkedList<XP> xpToRun;
    private LinkedList<XP> xpDone;
    private XP currentXP;
    private LinkedList<ComponentProperties> componentToInstall;
    private RequestCallback whenCtxReady;


    public RequestHandler getRequestHandler(final Request request) {
        return new ExperimenterRequestHandler(request, this);
    }

    @Override
    public void start() {
        super.start();
        componentToInstall = new LinkedList<>();
        KevoreeFactory kevFactory = new DefaultKevoreeFactory();
        cloner = kevFactory.createModelCloner();
    }

    @Override
    public void modelUpdated() {
        if (isFirstModelUpdate()) {
            registerXPUser();
            sendRequest(new Request(getFullId(), getNode() + ".http", getCurrentTime(),
                            "addHandler", new Object[]{"/experimenter", getFullId(), true}),
                    new ShowIfErrorCallback());

            if (!xpFile.equals("")) {
                xpToRun = loadXPProperties();
                xpDone = new LinkedList<>();
                currentXP = null;
                setupUser(new RequestCallback() {
                    @Override
                    public void success(Object o) {
                        runNextXp();
                    }

                    @Override
                    public void error(Error error) {
                        logError(error.toString());
                    }
                });
            }
        }
        super.modelUpdated();
    }


    @Input
    public void getNotif(String notifStr) {
        JsonObject jsonNotif = JsonObject.readFrom(notifStr);
        if (jsonNotif.get("dest").asString().equals("*")
                && jsonNotif.get("src").asString().equals(getNode() + ".context")) {
            Notif notif = new Notif(jsonNotif);
            if (notif.getContent() instanceof String
                    && Status.valueOf((String) notif.getContent()).equals(Status.READY)) {
                if (whenCtxReady != null) {
                    whenCtxReady.success(notif.getContent());
                }
            }
        }
    }


    private LinkedList<XP> loadXPProperties() {
        LinkedList<XP> xpList = new LinkedList<>();
        JsonObject jsonXpdetails = JsonObject.readFrom(FileHelper.fileToString(
                new File(System.getProperty("activehome.home") + "/" + xpFile)));
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
                xpList.add(new XP(this, jsonXp.asObject()));
            }
        }
        return xpList;
    }

    private void registerXPUser() {
        sendRequest(new Request(getFullId(), getNode() + ".auth", getCurrentTime(),
                        "register", new Object[]{"xpuser", "xpuser", getNode(), "admin,user",
                        "org.activehome.user.emulator.EUser/0.0.1-SNAPSHOT"}),
                new ShowIfErrorCallback());
    }

    private void runNextXp() {
        if (currentXP != null) {
            if (!currentXP.allIterationsDone()) {
                initAndStartTime();
            } else {
                JsonObject setting = currentXP.nextSetting();
                if (setting != null) {
                    run();
                } else {
                    xpDone.add(currentXP);
                    currentXP = xpToRun.pollFirst();
                    if (currentXP != null) {
                        run();
                    } else {
                        allXPCompleted();
                    }
                }
            }
        } else if (xpToRun != null && xpToRun.size() > 0) {
            currentXP = xpToRun.pollFirst();
            run();
        }
    }

    public void getResults(XP xp) {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        String[] metrics = new String[]{"cost.elec.import.1d#corrected,0",
                "benefits.elec.generation.1d#corrected,0",
                "benefits.elec.export.1d#corrected,0",
                "cost.elec.1d#corrected,0",
                "energy.1d.import#corrected,0",
                "energy.1d.export#corrected,0",
                "energy.1d.gen#corrected,0",
                "energy.1d.cons#corrected,0",
                "energy.1d.cons.bg#corrected,0",
                "energy.1d.cons.inter#corrected,0",
                "energy.1d.cons.bg.baseload#corrected,0"};

        String labels = "name,source,iteration,start,duration";
        for (String str : metrics) labels += "," + str;
        labels += "\n";

        FileHelper.save(labels, xp.getProperties().getString("resultFileName", "defaultResultFile.csv"));
        Request ctxReq = new Request(getFullId(), getNode() + ".context", getCurrentTime(),
                "extractSchedule", new Object[]{xp.getStartTS(), xp.getXpDuration(), HOUR, metrics});
        sendRequest(ctxReq, new RequestCallback() {
            @Override
            public void success(Object obj) {
                Schedule resultSchedule = (Schedule) obj;
                long time = xp.getStartTS();
                while (time < xp.getStartTS() + xp.getXpDuration()) {
                    String strVals = xp.getCurrentSetting().getString("settingName", "")
                            + "," + xp.getProperties().getString("source", "") + ",0,"
                            + strLocalTime(time) + "," + DAY;
                    for (String metric : metrics) {
                        strVals += ",";
                        MetricRecord mr = resultSchedule.getMetricRecordMap().get(metric.split("#")[0]);
                        int i = 0;
                        while (i < mr.getRecords().size() && mr.getRecords().get(i).getTS() + mr.getStartTime() < time) {
                            i++;
                        }
                        if (i < mr.getRecords().size()) {
                            strVals += mr.getRecords().get(i).getValue();
                        }
                    }
                    strVals += "\n";
                    FileHelper.save(strVals, xp.getProperties().getString("resultFileName", "defaultResultFile.csv"));
                    time += DAY;
                }

            }

            @Override
            public void error(Error error) {
                logError(error.toString());
            }
        });
    }

    private void run() {
        JsonObject properties = currentXP.getProperties();
        properties.add("source", currentXP.getSource());
        JsonObject data = getDataSource().asObject().get("dataSrc").asObject()
                .get(currentXP.getSource()).asObject();
        JsonArray array = new JsonArray();
        for (String key : data.asObject().names()) {
            array.add(data.get(key));
        }
        properties.add("appToStart", array);
        setProperties(properties, xpUser(), new RequestCallback() {
            @Override
            public void success(Object o) {
                initAndStartTime();
            }

            @Override
            public void error(Error error) {
                logError(error.toString());
            }
        });
    }

    protected UserInfo xpUser() {
        return new UserInfo("xpuser", new String[]{"user"},
                "ah", "org.activehome.user.emulator.EUser/0.0.1-SNAPSHOT");
    }

    public void setupUser(final RequestCallback callback) {
        UserInfo userInfo = xpUser();
        ComponentProperties cp = new ComponentProperties(userInfo.getUserType(),
                userInfo.getId(), new JsonObject(), new String[]{getNode()});
        Request req = new Request(getFullId(), getNode() + ".linker",
                getCurrentTime(), "startComponent", new Object[]{cp, userInfo});
        sendRequest(req, new RequestCallback() {
            public void success(Object result) {
                callback.success(true);
            }

            public void error(Error result) {
                callback.error(new Error(ErrorType.START_ERROR,
                        "Unable to start the manager for this user."));
            }
        });
    }

    private void allXPCompleted() {
        logInfo("all XP completed!");
    }

    @Override
    public void onInit() {
        super.onInit();
        registerXPUser();
    }

    @Override
    public void onStopTime() {
        super.onStopTime();
        currentXP.stop(new RequestCallback() {
            @Override
            public void success(Object o) {
                logInfo("XP done.");
                runNextXp();
            }

            @Override
            public void error(Error error) {
                logError(error.toString());
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

        boolean predictive = properties.get("predictive") != null && properties.get("predictive").asBoolean();
        boolean reactive = properties.get("reactive") != null && properties.get("reactive").asBoolean();
        boolean collab = properties.get("collab") != null && properties.get("collab").asBoolean();

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

    private void applyChanges(final JsonObject properties,
                              final UserInfo userInfo,
                              final RequestCallback callback) {
        removeAllXPComponents(userInfo, new RequestCallback() {
            public void success(final Object result) {
                installAppliances(userInfo, new RequestCallback() {
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
                evaluatorAttr.add("bindingXp", "getRequest>Experimenter.pushRequest,"
                        + "pushResponse>Experimenter.getResponse");
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
                    "org.activehome.energy.io.emulator.EBaseLoad", "baseload", attr, new String[]{});
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
            Request request = new Request(getFullId(), getNode() + ".linker", getCurrentTime(),
                    "updateComponentAttribute", new Object[]{userInfo.getId(),
                    "tableName", properties.get("source").asString()});
            sendRequest(request, new RequestCallback() {
                public void success(final Object result) {
                    Request request = new Request(getFullId(), getNode() + ".linker", getCurrentTime(),
                            "updateComponentAttribute", new Object[]{userInfo.getId(),
                            "urlSQLSource", urlSQLSource});
                    sendRequest(request, new RequestCallback() {
                        public void success(final Object result) {
                            installAppliances(userInfo, callback);
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

    public void removeAllXPComponents(final UserInfo userInfo,
                                      final RequestCallback callback) {
        Request request = new Request(getFullId(), getNode() + ".linker", getCurrentTime(),
                "stopComponentByType", new Object[]{new String[]{"IO", "Predictor", "BruteForceScheduler",
                "Evaluator", "Planner", "PowerBalancer"}});
        sendRequest(request, new RequestCallback() {
            public void success(final Object result) {
                installAppliances(userInfo, callback);
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
    public void installAppliances(final UserInfo userInfo,
                                  final RequestCallback callback) {
        if (componentToInstall.size() > 0) {
            Request request = new Request(getFullId(), getNode() + ".linker", getCurrentTime(),
                    "startComponent", new Object[]{componentToInstall.poll(), userInfo});
            sendRequest(request, new RequestCallback() {
                public void success(final Object result) {
                    installAppliances(userInfo, callback);
                }

                public void error(final Error result) {
                    callback.error(result);
                }
            });
        } else {
            callback.success(true);
        }
    }

    /**
     * List of data source that can be used for simulation
     *
     * @return {dataSrc:{srcId1:{appId1:{ComponentType}, appId2:{ComponentType}...}, srcId2:...}}
     */
    public JsonValue getDataSource() {
        Connection dbConnect = HelperMySQL.connect(urlSQLSource);
        JsonObject dataSource = new JsonObject();

        String query = "SELECT metricID, userID, type, properties FROM sources";

        try {
            ResultSet result = dbConnect.prepareStatement(query).executeQuery();
            while (result.next()) {
                String userID = result.getString("userID");
                String type = result.getString("type");
                String id = result.getString("metricID");
                JsonObject properties;
                if (result.getString("properties").compareTo("") != 0) {
                    properties = JsonObject.readFrom(result.getString("properties"));
                } else {
                    properties = new JsonObject();
                }

                if (id.compareTo("") != 0 && type.compareTo("") != 0) {
                    JsonValue json = dataSource.get(userID);
                    if (json == null) {
                        dataSource.add(userID, new JsonObject());
                        json = dataSource.get(userID).asObject();
                    }
                    json.asObject().add(id, new ComponentProperties(type, id, properties, new String[]{}).toJson());
                }
            }
        } catch (SQLException e) {
            Log.error(e.getMessage());
            e.printStackTrace();
        }

        JsonObject data = new JsonObject();
        data.add("dataSrc", dataSource);
        return data;
    }

    public void getTopology(RequestCallback callback) {
        UUIDModel model = getModelService().getCurrentModel();
        ContainerRoot localModel = cloner.clone(model.getModel());
        if (localModel != null) {
            JsonObject result = new JsonObject();
            JsonArray topo = new JsonArray();
            ContainerNode node = localModel.findNodesByID(context.getNodeName());
            if (node != null) {
                node.getComponents().stream()
                        .forEach(ci -> {
                            JsonObject comp = new JsonObject();
                            comp.add("compId", ci.getName());
                            JsonArray types = new JsonArray();
                            buildTypeList(ci.getTypeDefinition()).forEach(types::add);
                            comp.add("td", types);
                            topo.add(comp);
                        });
            }
            result.add("topology", topo);
            callback.success(result);
        } else {
            callback.error(new Error(ErrorType.MODEL_NULL, "The model is null"));
        }
    }

    public void getComponentView(String compId, RequestCallback callback) {
        UUIDModel model = getModelService().getCurrentModel();
        ContainerRoot localModel = cloner.clone(model.getModel());
        if (localModel != null) {
            JsonObject result = new JsonObject();
            JsonObject comp = new JsonObject();
            ContainerNode node = localModel.findNodesByID(context.getNodeName());
            if (node != null) {
                ComponentInstance ci = node.findComponentsByID(compId);
                if (ci != null) {
                    comp.add("compId", ci.getName());
                    JsonArray types = new JsonArray();
                    buildTypeList(ci.getTypeDefinition()).forEach(types::add);
                    comp.add("types", types);
                    HashMap<String, String> attrMap = ModelHelper.getComponentAttributes(ci);
                    for (String key : attrMap.keySet()) {
                        comp.add(key, attrMap.get(key));
                    }
                    if (ci.getTypeDefinition().getDictionaryType() != null) {
                        DictionaryType dt = ci.getTypeDefinition().getDictionaryType();
                        for (DictionaryAttribute da : dt.getAttributes()) {
                            if (comp.get(da.getName()) == null) {
                                if (da.getName().equals("metrics")) {
                                    comp.add(da.getName(), da.getDefaultValue().replaceAll("<compId>", ci.getName()));
                                } else {
                                    comp.add(da.getName(), da.getDefaultValue());
                                }
                            }
                        }
                    }
                }
            }
            result.add("component", comp);
            callback.success(result);
        } else {
            callback.error(new Error(ErrorType.MODEL_NULL, "The model is null"));
        }
    }

    private LinkedList<String> buildTypeList(TypeDefinition td) {
        LinkedList types = new LinkedList();
        types.add(td.getName());
        for (TypeDefinition supTd : td.getSuperTypes()) {
            types.addAll(buildTypeList(supTd));
        }
        return types;
    }


    /**
     * @param scheduledRequest The ScheduledRequest to send
     */
    public final void sendToTaskScheduler(final Request scheduledRequest, RequestCallback callback) {
        if (toSchedule != null && toSchedule.getConnectedBindingsSize() > 0) {
            if (callback != null) {
                getWaitingRequest().put(scheduledRequest.getId(), callback);
            }
            toSchedule.send(scheduledRequest.toString(), null);
        }
    }

    private void initAndStartTime() {
        JsonObject properties = new JsonObject();
        properties.add("startTS", currentXP.getStartTS());
        properties.add("zip", currentXP.getZip());
        Request timeReq = new Request(getFullId(), getNode() + ".timekeeper", getCurrentTime(),
                "setProperties", new Object[]{properties});
        sendRequest(timeReq, new RequestCallback() {
            public void success(final Object result) {
                whenCtxReady = new RequestCallback() {
                    @Override
                    public void success(Object o) {
                        logInfo("Context ready, starting XP...");
                        currentXP.init();
                        Request startReq = new Request(getFullId(), getNode()
                                + ".timekeeper", getCurrentTime(), "startTime");
                        sendRequest(startReq, new RequestCallback() {
                            public void success(final Object result) {
                                currentXP.start();
                            }

                            public void error(final Error result) {
                                logError(result.toString());
                            }
                        });
                    }

                    public void error(Error error) {
                    }
                };
                logInfo("Waiting for the context to be ready...");
            }

            public void error(final Error result) {
                logError(result.toString());
            }
        });
    }


//    public void initXp(final LinkedList<String> params,
//                       final UserInfo userInfo,
//                       final RequestCallback callback) {
//        HashMap<String, Object> paramsMap = new HashMap<>();
//        paramsMap.put("start", null);
//        paramsMap.put("end", null);
//        paramsMap.put("zip", "x300");
//        paramsMap.put("src", null);
//        paramsMap.put("adaptation", "");
//        paramsMap.put("horizon", "1d");
//        paramsMap.put("granularity", "1h");
////        paramsMap.put("objectives", "MinimizeUserDisruption,MinimizeUserCosts,MinimizeEnvironmentalImpact");
//        paramsMap.put("objectives", "MinimizeEnvironmentalImpact");
//        paramsMap.put("forecaster", "org.activehome.prediction.Eforecaster");
//        for (String param : params) {
//            String[] paramParts = param.split("=");
//            String val = paramParts[1];
//            if ((val.startsWith("\"") && val.endsWith("\""))
//                    || ((val.startsWith("'") && val.endsWith("'")))) {
//                val = val.substring(1, val.length() - 1);
//            }
//            if (paramParts.length == 2) paramsMap.put(paramParts[0], val);
//        }
//
//        boolean allRequirements = true;
//        for (String param : paramsMap.keySet()) {
//            if (paramsMap.get(param) == null) {
//                allRequirements = false;
//                break;
//            }
//        }
//
//        if (allRequirements) {
//            initXp(paramsMap, userInfo, callback);
//        } else {
//            callback.error(new Error(ErrorType.WRONG_PARAM,
//                    "Missing required params start, end or src."));
//        }
//
//    }

//    public void getContext(final LinkedList<String> params,
//                           final RequestCallback callback) {
//        String[] metricArray = null;
//        for (String param : params) {
//            if (param.startsWith("metrics=")) {
//                metricArray = param.replace("metrics=", "").split(",");
//            }
//        }
//        if (metricArray != null) {
//            Request ctxReq = new Request(getFullId(), getNode() + ".context", getCurrentTime(), "getLastData",
//                    new Object[]{metricArray});
//            sendRequest(ctxReq, new RequestCallback() {
//                public void success(final Object result) {
//                    callback.success(result);
//                }
//
//                public void error(final Error result) {
//                    callback.error(result);
//                }
//            });
//        } else {
//            callback.error(new Error(ErrorType.WRONG_PARAM, "Missing metric arg."));
//        }
//    }

//    public void startXp(final LinkedList<String> params,
//                        final RequestCallback callback) {
//        Request startReq = new Request(getFullId(), getNode() + ".timekeeper", getCurrentTime(), "startTime");
//        sendRequest(startReq, new RequestCallback() {
//            public void success(final Object result) {
//                callback.success("Xp running...");
//            }
//
//            public void error(final Error result) {
//                callback.error(result);
//            }
//        });
//    }
//
//    public void stopXp(final LinkedList<String> params,
//                       final RequestCallback callback) {
//        Request stopReq = new Request(getFullId(), getNode() + ".timekeeper", getCurrentTime(), "stopTime");
//        sendRequest(stopReq, new RequestCallback() {
//            public void success(final Object result) {
//                callback.success("Xp stopped.");
//            }
//
//            public void error(final Error result) {
//                callback.error(result);
//            }
//        });
//    }

//    public void setupGrid(final String hostId,
//                          final String gridId) {
//        String script = "add {host}.{node} : JavaNode\n" +
//                "set {node}.log = \"DEBUG\"\n" +
//                "\n" +
//                "add {node}.timekeeper : org.activehome.service.time.TimeKeeper\n" +
//                "set {node}.timekeeper.startDate = \"2013-07-19 00:00:00\"\n" +
//                "set {node}.timekeeper.stopDate = \"2013-07-31 00:00:00\"\n" +
//                "set {node}.timekeeper.zip = \"x300\"\n" +
//                "\n" +
//                "add chan_pushResponse_timekeeper : AsyncBroadcast\n" +
//                "bind {node}.timekeeper.pushResponse chan_pushResponse_timekeeper\n" +
//                "\n" +
//                "add chan_pushNotif_timekeeper : RemoteWSChan\n" +
//                "set chan_pushNotif_timekeeper.host = \"ws.kevoree.org\"\n" +
//                "set chan_pushNotif_timekeeper.port = \"80\"\n" +
//                "set chan_pushNotif_timekeeper.uuid = \"" + UUID.randomUUID() + "\"\n" +
//                "set chan_pushNotif_timekeeper.path = \"/activehome\"\n" +
//                "bind {node}.timekeeper.pushNotif chan_pushNotif_timekeeper\n" +
//                "\n" +
//                "add {node}.mg : org.activehome.energy.emulator.energy.microgrid.MicroGrid\n" +
//                "\n" +
//                "add chan_time_mg : RemoteWSChan\n" +
//                "set chan_time_mg.host = \"ws.kevoree.org\"\n" +
//                "set chan_time_mg.port = \"80\"\n" +
//                "set chan_time_mg.uuid = \"" + UUID.randomUUID() + "\"\n" +
//                "set chan_time_mg.path = \"/activehome\"\n" +
//                "bind {node}.timekeeper.tic chan_time_ws\n" +
//                "bind {node}.mg.time chan_time_mg\n" +
//                "\n" +
//                "add chan_getRequest_mg : RemoteWSChan\n" +
//                "set chan_getRequest_mg.host = \"ws.kevoree.org\"\n" +
//                "set chan_getRequest_mg.port = \"80\"\n" +
//                "set chan_getRequest_mg.uuid = \"" + UUID.randomUUID() + "\"\n" +
//                "set chan_getRequest_mg.path = \"/activehome\"\n" +
//                "bind {node}.mg.getRequest chan_getRequest_mg\n" +
//                "bind {node}.http.pushRequest chan_getRequest_mg\n" +
//                "\n" +
//                "bind {node}.mg.pushRequest chan_getRequest_http\n" +
//                "bind {node}.mg.getResponse chan_pushResponse_http\n" +
//                "bind {node}.mg.pushResponse chan_sendOutside_http\n" +
//                "\n" +
//                "add {node}.context : org.activehome.context.live.LiveContext\n" +
//                "bind {node}.context.getRequest chan_pushRequest_ws\n" +
//                "bind {node}.context.getResponse chan_pushResponse_ws\n" +
//                "bind {node}.context.pushDataOutside chan_sendOutside_ws\n" +
//                "bind {node}.context.pushDataOutside chan_sendOutside_http\n" +
//                "add chan_pushDataToSystem_context : AsyncBroadcast\n" +
//                "bind {node}.context.pushDataToSystem chan_pushDataToSystem_context\n" +
//                "bind {node}.context.getNotif chan_pushNotif_ws\n" +
//                "bind {node}.context.getNotif chan_pushNotif_http\n" +
//                "add chan_time_{node}_context : RemoteWSChan\n" +
//                "set chan_time_{node}_context.host = \"ws.kevoree.org\"\n" +
//                "set chan_time_{node}_context.port = \"80\"\n" +
//                "set chan_time_{node}_context.uuid = \"" + UUID.randomUUID() + "\"\n" +
//                "set chan_time_{node}_context.path = \"/activehome\"\n" +
//                "bind {node}.timekeeper.tic chan_time_{node}_context\n" +
//                "bind {node}.context.time chan_time_{node}_context\n" +
//                "\n" +
//                "bind {node}.context.getNotif chan_pushNotif_timekeeper\n" +
//                "bind {node}.context.pushRequest chan_getRequest_http\n" +
//                "\n";
//
//        script += "\nattach {node} sync\n";
//
//        script += script.replaceAll("\\{node\\}", gridId)
//                .replaceAll("\\{host\\}", hostId);
//
//        modelService.submitScript(script, applied -> Log.info("Model updated after adding household: " + applied));
//    }

//    public LinkedList<String> extractCmdArgs(final String cmdStr) {
//        LinkedList<String> matchList = new LinkedList<>();
//        // catch: arg="dbquotes with space" arg='quotes with space' argAlone (including arg=val) 'quote alone' "dbquotes alone"
//        Pattern regex = Pattern.compile("(([^\\s\"'=]+)(=)(\"([^\"]*)\"))|(([^\\s\"'=]+)(=)('([^']*)'))|([^\\s\"']+)|(\"([^\"]*)\")|('([^']*)')");
//        Matcher regexMatcher = regex.matcher(cmdStr);
//        while (regexMatcher.find()) {
//            matchList.add(regexMatcher.group());
//        }
//        return matchList;
//    }


}
