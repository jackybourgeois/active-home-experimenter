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
import org.activehome.com.Request;
import org.activehome.com.RequestCallback;
import org.activehome.com.error.Error;

import java.util.LinkedList;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class XpCtrl {

    private Experimenter exper;
    private XpSetup setup;
    private XP currentXP;
    private LinkedList<XP> xpToRun;
    private LinkedList<XP> xpDone;
    private RequestCallback whenCtxReady;

    public XpCtrl(final Experimenter experimenter,
                  final XpSetup setup) {
        exper = experimenter;
        this.setup = setup;
        xpToRun = setup.loadXPProperties();
        xpDone = new LinkedList<>();
        currentXP = null;
    }

    /**
     * Send setProperties to Timekeeper to init the time,
     * wait that the context is ready (can take a while if cleaning previous simulation)
     * then send call xp start
     */
    private void initAndStartTime() {
        JsonObject properties = new JsonObject();
        properties.add("startTS", currentXP.getStartTS());
        properties.add("zip", currentXP.getZip());
        Request timeReq = exper.buildRequest("timekeeper", "setProperties", new Object[]{properties});
        exper.sendRequest(timeReq, new RequestCallback() {
            public void success(final Object result) {
                whenCtxReady = new RequestCallback() {
                    @Override
                    public void success(Object o) {
                        exper.logInfo("Context ready, starting XP... time zone offset: " + exper.getTic().getTimezone());
                        currentXP.init();
                        Request startReq = exper.buildRequest("timekeeper", "startTime");
                        exper.sendRequest(startReq, new RequestCallback() {
                            public void success(final Object result) {
                                currentXP.start();
                            }

                            public void error(final Error result) {
                                exper.logError(result.toString());
                            }
                        });
                    }

                    public void error(Error error) {
                    }
                };
                exper.logInfo("Waiting for the context to be ready...");
            }

            public void error(final Error result) {
                exper.logError(result.toString());
            }
        });
    }

    void runNextXp() {
        if (currentXP != null) {
            if (!currentXP.allIterationsDone()) {
                initAndStartTime();
            } else {
                JsonObject setting = currentXP.nextSetting();
                if (setting != null) {
                    run();
                } else {
                    currentXP.saveResults();
                    xpDone.add(currentXP);
                    currentXP = xpToRun.pollFirst();
                    if (currentXP != null) {
                        run();
                    } else {
                        exper.logInfo("all XP completed!");
                    }
                }
            }
        } else if (xpToRun != null && xpToRun.size() > 0) {
            currentXP = xpToRun.pollFirst();
            run();
        }
    }

    private void run() {

        JsonObject properties = currentXP.getProperties();
        properties.add("source", currentXP.getSource());
        JsonObject data = exper.getDataSource().asObject().get("dataSrc").asObject()
                .get(currentXP.getSource()).asObject();
        JsonArray array = new JsonArray();
        for (String key : data.asObject().names()) {
            array.add(data.get(key));
        }
        properties.add("appToStart", array);
        setup.setProperties(properties, exper.xpUser(), new RequestCallback() {
            @Override
            public void success(Object o) {
                initAndStartTime();
            }

            @Override
            public void error(Error error) {
                exper.logError(error.toString());
            }
        });
    }

    public XP getCurrentXP() {
        return currentXP;
    }

    public RequestCallback getCtxReadyCallback() {
        return whenCtxReady;
    }
}
