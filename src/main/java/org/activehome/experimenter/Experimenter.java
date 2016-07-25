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
import org.activehome.context.data.UserInfo;
import org.activehome.context.helper.ModelHelper;
import org.activehome.evaluator.EvaluationReport;
import org.activehome.mysql.HelperMySQL;
import org.activehome.service.RequestHandler;
import org.activehome.service.Service;
import org.kevoree.annotation.ComponentType;
import org.kevoree.annotation.Input;
import org.kevoree.annotation.Output;
import org.kevoree.annotation.Param;
import org.kevoree.api.handler.UUIDModel;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.factory.KevoreeFactory;
import org.kevoree.log.Log;
import org.kevoree.pmodeling.api.ModelCloner;
import org.kevoree.ContainerRoot;
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class Experimenter extends Service {

    @Output
    protected org.kevoree.api.Port toSchedule;
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
    private XpCtrl ctrl;
    private XpSetup setup;

    private ModelCloner cloner;

    // component lifecycle

    @Override
    public void start() {
        super.start();
        KevoreeFactory kevFactory = new DefaultKevoreeFactory();
        cloner = kevFactory.createModelCloner();
    }

    @Override
    public void modelUpdated() {
        if (isFirstModelUpdate()) {
            listenAPI(getNode() + ".http", "/experimenter", true);

            File xp = new File(System.getProperty("active-home.home") + "/" + xpFile);
            if (xp.exists() && xp.isFile()) {
                setup = new XpSetup(this, urlSQLSource, xp);
            } else {
                setup = new XpSetup(this);
            }
            ctrl = new XpCtrl(this, setup);
            registerXPUser();

            setup.setupUser(new RequestCallback() {
                @Override
                public void success(Object o) {
                    ctrl.runNextXp();
                }

                @Override
                public void error(Error error) {
                    logError(error.toString());
                }
            });

        }
        super.modelUpdated();
    }

    // time lifecycle

    /**
     * on init, make sure there is a default user in the context
     */
    @Override
    public void onInit() {
        super.onInit();
        registerXPUser();
    }

    @Override
    public void onStopTime() {
        super.onStopTime();
        if (ctrl.getCurrentXP()!=null) {
            ctrl.getCurrentXP().stop(new RequestCallback() {
                @Override
                public void success(Object o) {
                    logInfo("XP done.");
                    ctrl.runNextXp();
                }

                @Override
                public void error(Error error) {
                    logError(error.toString());
                }
            });
        }
    }

    // inputs

    /**
     * Receive notifications:
     * - from the context when it is ready
     * - from evaluator for new reports
     *
     * @param notifStr the notification as string
     */
    @Input
    public void getNotif(String notifStr) {
        JsonObject jsonNotif = JsonObject.readFrom(notifStr);
        if (jsonNotif.get("dest").asString().equals("*")
                && jsonNotif.get("src").asString().equals(getNode() + ".context")) {
            Notif notif = new Notif(jsonNotif);
            if (notif.getContent() instanceof String
                    && Status.valueOf((String) notif.getContent()).equals(Status.READY)) {
                if (ctrl.getCtxReadyCallback() != null) {
                    ctrl.getCtxReadyCallback().success(notif.getContent());
                }
            }
        }
    }

    /**
     * Recieve notification of new evaluation report
     *
     * @param notifStr notif as String
     */
    @Input
    public void getReport(String notifStr) {
        JsonObject jsonNotif = JsonObject.readFrom(notifStr);
        Notif notif = new Notif(jsonNotif);
        if (notif.getContent() instanceof EvaluationReport) {
            ctrl.getCurrentXP().manageEvaluationReport((EvaluationReport) notif.getContent());
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
                    json.asObject().add(id, new ComponentProperties(type, id,
                            properties, new String[]{}).toJson());
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

    public void getTopology(final RequestCallback callback) {
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

    public void getComponentView(final String compId,
                                 final RequestCallback callback) {
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

    private LinkedList<String> buildTypeList(final TypeDefinition td) {
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
    public final void sendToTaskScheduler(final Request scheduledRequest,
                                          final RequestCallback callback) {
        if (toSchedule != null && toSchedule.getConnectedBindingsSize() > 0) {
            if (callback != null) {
                getWaitingRequest().put(scheduledRequest.getId(), callback);
            }
            toSchedule.send(scheduledRequest.toString(), null);
        }
    }


    public RequestHandler getRequestHandler(final Request request) {
        return new ExperimenterRequestHandler(request, this);
    }


    /**
     * Fake user for the xp
     *
     * @return
     */
    protected UserInfo xpUser() {
        return new UserInfo("xpuser", new String[]{"admin,user"},
                "ah", setup.getUserType());
    }

    /**
     * register a fake user for the xp
     */
    private void registerXPUser() {
        sendRequest(new Request(getFullId(), getNode() + ".auth", getCurrentTime(),
                        "register", new Object[]{"xpuser", "xpuser", getNode(), "admin,user",
                        setup.getUserType()}),
                new ShowIfErrorCallback());
    }

    /**
     * Sugary to make request more readable
     *
     * @param localDest The destination in the current node only
     * @param method    The requested method
     * @param params    The method's params
     * @return a request
     */
    public Request buildRequest(final String localDest,
                                final String method,
                                final Object[] params) {
        return new Request(getFullId(), getNode() + "." + localDest,
                getCurrentTime(), method, params);
    }

    public Request buildRequest(final String localDest,
                                final String method) {
        return new Request(getFullId(), getNode() + "." + localDest,
                getCurrentTime(), method);
    }

    public XpSetup getSetup() {
        return setup;
    }

    public XpCtrl getCtrl() {
        return ctrl;
    }

    public LinkedList<String> extractCmdArgs(final String cmdStr) {
        LinkedList<String> matchList = new LinkedList<>();
        // catch: arg="dbquotes with space" arg='quotes with space' argAlone (including arg=val) 'quote alone' "dbquotes alone"
        Pattern regex = Pattern.compile("(([^\\s\"'=]+)(=)(\"([^\"]*)\"))|(([^\\s\"'=]+)(=)('([^']*)'))|([^\\s\"']+)|(\"([^\"]*)\")|('([^']*)')");
        Matcher regexMatcher = regex.matcher(cmdStr);
        while (regexMatcher.find()) {
            matchList.add(regexMatcher.group());
        }
        return matchList;
    }
}
