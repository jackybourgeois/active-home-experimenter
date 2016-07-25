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


import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.activehome.com.Request;
import org.activehome.com.RequestCallback;
import org.activehome.com.error.Error;
import org.activehome.com.error.ErrorType;
import org.activehome.service.RequestHandler;
import org.activehome.tools.file.FileHelper;
import org.activehome.tools.file.TypeMime;
import org.activehome.context.data.UserInfo;

import java.util.Date;
import java.util.LinkedList;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class ExperimenterRequestHandler implements RequestHandler {

    /**
     * The request to handle.
     */
    private final Request request;
    /**
     * The service that will handle it.
     */
    private final Experimenter service;

    /**
     * @param theRequest The request to handle
     * @param theService The service that will execute it
     */
    public ExperimenterRequestHandler(final Request theRequest,
                                      final Experimenter theService) {
        request = theRequest;
        service = theService;
    }

    // init start="2013-07-01 00:00:00" end="2013-07-03 00:00:00" zip="x300" src="48" adaptation="RPCollab"
    public final void execute(final String cmdStr,
                              final RequestCallback callback) {
        LinkedList<String> paramList = service.extractCmdArgs(cmdStr);
        if (paramList.size() >= 0) {
            String method = paramList.pollFirst();
            switch (method) {
//                case "init":
//                    service.initXp(paramList, (UserInfo) request.getEnviElem().get("userInfo"), callback);
//                    break;
//                case "start":
//                    service.startXp(paramList, callback);
//                    break;
//                case "stop":
//                    service.stopXp(paramList, callback);
//                    break;
//                case "get":
//                    service.getContext(paramList, callback);
//                    break;
                default:
                    callback.error(new Error(ErrorType.NO_SUCH_METHOD, "Unknown command " + method));
            }
        } else {
            callback.error(new Error(ErrorType.NO_SUCH_METHOD, "Empty request"));
        }
    }

    public void html(final RequestCallback callback) {
        JsonObject wrap = new JsonObject();
        wrap.add("name", "ah-experimenter");
        wrap.add("url", service.getId() + "/ah-experimenter.html");
        wrap.add("title", "Active Home - Experimenter");
        wrap.add("description", "Active Home Experimenter");

        JsonObject json = new JsonObject();
        json.add("wrap", wrap);
        callback.success(json);
    }

    public final void file(final String fileName,
                           final RequestCallback callback) {
        if (fileName.endsWith("ah-experimenter.html")) {
            Request urlWSReq = new Request(service.getFullId(),
                    service.getNode() + ".ws", service.getCurrentTime(), "getURI");
            service.sendRequest(urlWSReq, new RequestCallback() {
                public void success(final Object wsURI) {
                    Request urlHTTPReq = new Request(service.getFullId(),
                            service.getNode() + ".http", service.getCurrentTime(), "getURI");
                    service.sendRequest(urlHTTPReq, new RequestCallback() {
                        public void success(final Object httpURI) {
                            String content = FileHelper.fileToString("ah-experimenter.html",
                                    getClass().getClassLoader());
                            content = content.replaceAll("\\$\\{id\\}", service.getId());
                            content = content.replaceAll("\\$\\{node\\}", service.getNode());
                            content = content.replaceAll("\\$\\{\\?t=\\}", "?t=" + new Date().getTime());
                            content = content.replaceAll("\\$\\{wsURI\\}", wsURI + "");
                            content = content.replaceAll("\\$\\{url\\}", httpURI + "");

                            JsonObject json = new JsonObject();
                            json.add("content", content);
                            json.add("mime", "text/html");
                            callback.success(json);
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
            String content = FileHelper.fileToString(fileName, getClass().getClassLoader());
            if (fileName.endsWith(".html")) {
                content = content.replaceAll("\\$\\{id\\}", service.getId());
                content = content.replaceAll("\\$\\{node\\}", service.getNode());
            }
            JsonObject json = new JsonObject();
            json.add("content", content);
            json.add("mime", TypeMime.valueOf(fileName.substring(
                    fileName.lastIndexOf(".") + 1, fileName.length())).getDesc());
            callback.success(json);
        }
    }


    public void setProperties(final JsonObject properties,
                              final RequestCallback callback) {
        service.getSetup().setProperties(properties,
                (UserInfo) request.getEnviElem().get("userInfo"), callback);
    }

    public JsonValue getDataSource() {
        return service.getDataSource();
    }

    public void getTopology(RequestCallback callback) {
        service.getTopology(callback);
    }

    public void getComponentView(String compId, RequestCallback callback) {
        service.getComponentView(compId, callback);
    }

}
