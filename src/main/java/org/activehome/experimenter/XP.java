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
import org.activehome.com.ScheduledRequest;
import org.activehome.com.ShowIfErrorCallback;
import org.activehome.context.data.Trigger;
import org.activehome.evaluator.EvaluationReport;
import org.activehome.tools.Convert;
import org.activehome.tools.file.FileHelper;
import org.kevoree.log.Log;

import java.io.File;
import java.util.LinkedList;
import java.util.TreeMap;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class XP {

    protected int zip;
    private JsonObject properties;
    private Experimenter experimenter;
    private long startTS;
    private int nbDays;
    private int iteration;
    private String src;
    private JsonArray settings;
    private RequestCallback callbackWhenAllEvalReceived;

    private int iterationId;
    private int settingIndex;
    private String resultFileName;
    /**
     * map of results: settingName/iteration/eval
     */
    private TreeMap<String, TreeMap<Integer, TreeMap<String, LinkedList<EvaluationReport>>>> resultMap;

    public XP(final Experimenter experimenter,
              final JsonObject json) {
        properties = json;
        this.experimenter = experimenter;
        this.startTS = json.get("start").asLong();
        this.nbDays = json.get("nbDays").asInt();

        this.settings = json.get("settings").asArray();
        this.iteration = json.get("iteration").asInt();
        this.zip = json.get("zip").asInt();
        this.resultFileName = json.get("resultFileName").asString();

        this.src = json.get("source").asString();
        settingIndex = 0;
        iterationId = 0;

        callbackWhenAllEvalReceived = null;
        resultMap = new TreeMap<>();
    }

    public boolean allIterationsDone() {
        return iterationId == iteration;
    }

    public JsonObject nextSetting() {
        if (settingIndex < settings.size() - 1) {
            settingIndex++;
            iterationId = 0;
            return settings.get(settingIndex).asObject();
        }
        return null;
    }

    public String getSource() {
        return src;
    }

    public long getStartTS() {
        return startTS;
    }

    /**
     * On init of each XP's iteration, subscribe to relevant metrics.
     */
    public final void init() {
        String[] metricArray = new String[]{"energy.cons", "power.cons.bg.*"};
        Request subscriptionReq = new Request(experimenter.getFullId(), experimenter.getNode() + ".context",
                experimenter.getCurrentTime(), "subscribe", new Object[]{metricArray, experimenter.getFullId()});

        subscriptionReq.getEnviElem().put("userInfo", experimenter.xpUser());
        experimenter.sendRequest(subscriptionReq, new ShowIfErrorCallback());

        iterationId++;
        if (resultMap.get(getCurrentSettingName()) == null) {
            resultMap.put(getCurrentSettingName(), new TreeMap<>());
        }
        if (resultMap.get(getCurrentSettingName()).get(iterationId) == null) {
            resultMap.get(getCurrentSettingName()).put(iterationId, new TreeMap<>());
        }
        log("initialized.");
    }

    /**
     * On start time, schedule a pause
     */
    public final void start() {
        long stopTime = startTS + getXpDuration();
        ScheduledRequest sr = new ScheduledRequest(experimenter.getFullId(),
                experimenter.getNode() + ".timekeeper", experimenter.getCurrentTime(),
                "stopTime", stopTime);
        log("send stop time to task scheduler: " + experimenter.strLocalTime(stopTime));
        experimenter.sendToTaskScheduler(sr, new ShowIfErrorCallback());
        configureTriggers();
        log("started.");
    }

    /**
     * On stop time, check if all evaluation report have been received,
     * or wait missing eval report
     */
    public final void stop(final RequestCallback callback) {
        log("iteration " + iterationId + " done.");
        if (checkAllEvalHaveBeenReceived()) {
            callbackWhenAllEvalReceived = null;
            callback.success(true);
        } else {
            callbackWhenAllEvalReceived = callback;
        }
    }

    public boolean checkAllEvalHaveBeenReceived() {
        String genVersion = "";
        String costVersion = "";
        String co2Version = "";
        if (resultMap.get(getCurrentSettingName()).get(iterationId).size() < 3) {
            log("waiting missing eval, number of eval type not matching the number of evaluator.");
            return false;
        }
        for (String eval : resultMap.get(getCurrentSettingName()).get(iterationId).keySet()) {
            LinkedList<EvaluationReport> reports = resultMap.get(getCurrentSettingName()).get(iterationId).get(eval);
            if (!((getXpDuration() / Experimenter.DAY) == reports.size())) {
                log("waiting missing eval from " + eval + ": "
                        + reports.size() + " instead of " + (getXpDuration() / Experimenter.DAY));
                return false;
            }
            if (eval.equals("EnergyEvaluator")) {
                genVersion = reports.getLast().getVersion();
            } else if (eval.equals("CostEvaluator")) {
                costVersion = reports.getLast().getVersion();
            } else if (eval.equals("EnvironmentalImpactEvaluator")) {
                co2Version = reports.getLast().getVersion();
            }
        }
        if (!genVersion.equals(costVersion)) {
            log("waiting corrected version of cost eval, genVersion: " + genVersion + " costVersion: " + costVersion);
            return false;
        } else if (!genVersion.equals(co2Version)) {
            log("waiting corrected version of co2 eval, genVersion: " + genVersion + " co2Version: " + co2Version);
            return false;
        }

        log("all eval received, we can go to the next xp! genVersion:" + genVersion + " costVersion: " + costVersion);
        return true;
    }

    public String resultLabels(final EvaluationReport report) {
        String labels = "src, setting, iteration, startTS, duration";
        for (String metric : report.getReportedMetrics().keySet()) {
            labels += "," + metric;
        }
        return labels;
    }

    public void saveResults() {
        String folder = experimenter.getSetup().getResultFolder();
        for (String setting : resultMap.keySet()) {
            for (Integer id : resultMap.get(setting).keySet()) {
                for (String eval : resultMap.get(setting).get(id).keySet()) {
                    LinkedList<EvaluationReport> reports = resultMap.get(setting).get(id).get(eval);
                    String fileName = folder + "/" + eval + ".csv";
                    String results = "";
                    if (reports != null && reports.size() > 0) {
                        if (!new File(fileName).exists()) {
                            results += resultLabels(reports.get(0)) + "\n";
                        }
                        for (EvaluationReport report : reports) {
                            results += getSource() + "," + setting + "," + id + ","
                                    + experimenter.strLocalTime(report.getSchedule().getStart()) + ","
                                    + report.getSchedule().getHorizon();
                            for (String val : report.getReportedMetrics().values()) {
                                results += "," + val;
                            }
                            results += "\n";
                        }
                        FileHelper.log(results, fileName);
                    }
                }
            }
        }
    }

    private String getCurrentSettingName() {
        return settings.get(settingIndex).asObject().get("settingName").asString();
    }

    protected final long getXpDuration() {
        return Convert.strDurationToMillisec(nbDays + "d");
    }

    private void configureTriggers() {

        Trigger ctrlGen = new Trigger("^power\\.gen\\.SolarPV$",
                "(${time.dayTime,true}==true)?${triggerValue}:0", "");

        Trigger bgTrigger = new Trigger("(^power\\.cons\\.bg\\.)+(.*?)",
                "sum(power.cons.bg.*)", "power.cons.bg");
        Trigger interTrigger = new Trigger("(^power\\.cons\\.inter\\.)+(.*?)",
                "sum(power.cons.inter.*)", "power.cons.inter");
        Trigger genTrigger = new Trigger("(^power\\.gen\\.)+(.*?)",
                "sum(power.gen.*)", "power.gen");
        Trigger consTrigger = new Trigger("^power\\.cons\\.(inter|bg)$",
                "${power.cons.inter,0}+${power.cons.bg,0}", "power.cons");
        Trigger balanceTrigger = new Trigger("(^power\\.cons$)|(^power\\.gen$)",
                "${power.cons,0}-${power.gen,0}", "power.balance");
        Trigger importTrigger = new Trigger("^power\\.balance$",
                "(${power.balance}>0)?${power.balance}:0", "power.import");
        Trigger exportTrigger = new Trigger("^power\\.balance$",
                "(${power.balance}<0)?(-1*${power.balance}):0", "power.export");

        Request triggerReq = experimenter.buildRequest("context", "addTriggers",
                new Object[]{new Trigger[]{ctrlGen, bgTrigger, interTrigger, consTrigger, genTrigger,
                        balanceTrigger, importTrigger, exportTrigger}});
        experimenter.sendRequest(triggerReq, new ShowIfErrorCallback());
    }

    public void log(String log) {
        Log.info("[XP " + src + "-" + getCurrentSettingName() + ", iteration: " + iterationId + "/" + iteration
                + ", nbDays: " + nbDays + "] " + log);
    }

    public int getZip() {
        return zip;
    }

    public JsonObject getProperties() {
        return properties;
    }

    public TreeMap<String, TreeMap<Integer, TreeMap<String, LinkedList<EvaluationReport>>>> getResultMap() {
        return resultMap;
    }

    public JsonObject getCurrentSetting() {
        return settings.get(settingIndex).asObject();
    }


    /**
     * Add a new report to the results
     *
     * @param report the new report
     */
    public void manageEvaluationReport(final EvaluationReport report) {
        log("managing new eval report from " + report.getEvaluatorName());
        TreeMap<String, LinkedList<EvaluationReport>> reportMap
                = resultMap.get(this.getCurrentSettingName()).get(iterationId);

        // check if previous report for this eval
        if (reportMap.get(report.getEvaluatorName()) == null) {
            reportMap.put(report.getEvaluatorName(), new LinkedList<>());
        }

        // check if existing report for this date, add or replace
        LinkedList<EvaluationReport> reports = reportMap.get(report.getEvaluatorName());
        if (reports.size() > 0 && reports.getLast().getSchedule().getStart() == report.getSchedule().getStart()) {

            log("updating report " + report.getEvaluatorName() + " with version " + report.getVersion()
                    + " current version: " + reports.get(reports.size() - 1).getVersion());
            reports.set(reports.size() - 1, report);
            log("updated report " + report.getEvaluatorName() + ": " + reports.get(reports.size() - 1).getVersion());
        } else {
            reports.addLast(report);
        }

        // generate charts of this report
        String name = report.getEvaluatorName() + "_" + getCurrentSettingName() + "_" + iterationId
                + "_" + report.getSchedule().getStart();
        String folder = experimenter.getSetup().getResultFolder();
        ResultChart.chartSchedule(report.getSchedule(), folder, name);

        if (callbackWhenAllEvalReceived != null) {
            if (checkAllEvalHaveBeenReceived()) {
                callbackWhenAllEvalReceived.success(true);
            }
        }
    }
}
