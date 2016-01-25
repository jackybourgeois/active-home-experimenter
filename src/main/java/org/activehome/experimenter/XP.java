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
import org.activehome.com.error.Error;
import org.activehome.context.data.Schedule;
import org.activehome.context.data.MetricRecord;
import org.activehome.context.data.Record;
import org.activehome.context.data.SampledRecord;
import org.activehome.context.data.Trigger;
import org.activehome.evaluator.EvaluationReport;
import org.activehome.tools.Convert;
import org.activehome.tools.file.FileHelper;
import org.kevoree.log.Log;

import java.util.TreeMap;

/**
 * Mock component to simulate behaviour and test components.
 *
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class XP {

    private JsonObject properties;
    private Experimenter experimenter;
    private long startTS;
    private int nbDays;
    private int iteration;
    private String src;
    private JsonArray settings;
    protected int zip;

    private int iterationId;
    private int settingIndex;
    private String resultFileName;
    private TreeMap<String, TreeMap<Integer, TreeMap<String, String>>> resultMap;

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

        resultMap = new TreeMap<>();
    }

    public boolean allIterationsDone() {
        return iterationId == iteration;
    }

    public JsonObject nextSetting() {
        if (settingIndex<settings.size()-1) {
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
        startTS = startTS - experimenter.getTic().getTimezone() * experimenter.HOUR;
        String[] metricArray = new String[]{"energy.cons","power.cons.bg.*"};
        Request subscriptionReq = new Request(experimenter.getFullId(), experimenter.getNode() + ".context",
                experimenter.getCurrentTime(), "subscribe", new Object[]{metricArray, experimenter.getFullId()});

        subscriptionReq.getEnviElem().put("userInfo", experimenter.xpUser());
        experimenter.sendRequest(subscriptionReq, new ShowIfErrorCallback());

        iterationId++;
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
     * On stop time, extract data from db source
     * and compare with the sum energy.cons
     */
    public final void stop(final RequestCallback callback) {
        log("iteration " + iterationId + " done.");
        experimenter.getResults(this);
        callback.success(true);
    }

    private void eval(String nameEval, RequestCallback callback) {
        System.out.println("eval " + nameEval);
        Request evalReq = new Request(experimenter.getFullId(),
                experimenter.getNode() + "." + nameEval, experimenter.getCurrentTime(),
                "evaluate", new Object[]{startTS, startTS + getXpDuration()});
        experimenter.sendRequest(evalReq, new RequestCallback() {
            @Override
            public void success(Object obj) {
                EvaluationReport report = (EvaluationReport) obj;
                String settingName = getCurrentSettingName();
                if (!resultMap.containsKey(settingName)) {
                    resultMap.put(settingName, new TreeMap<>());
                }
                if (!resultMap.get(settingName).containsKey(iterationId)) {
                    resultMap.get(settingName).put(iterationId, new TreeMap<>());
                }
                resultMap.get(settingName).get(iterationId).putAll(report.getReportedMetrics());
                chartSchedule(report.getSchedule(), settingName + "_" + src + "_" + iterationId + "_" + nameEval);

                callback.success(report);
            }

            @Override
            public void error(Error error) {
                log(error.toString());
            }
        });
    }

    public void writeResultLabels() {
        StringBuilder sb = new StringBuilder();
        sb.append("src,setting,iteration,startTS,duration");
        for (String src : resultMap.keySet()) {
            for (int it : resultMap.get(src).keySet()) {
                for (String metric : resultMap.get(src).get(it).keySet()) {
                    sb.append(",").append(metric);
                }
                break;
            }
            break;
        }
        FileHelper.logln(sb.toString(), resultFileName);
    }

    public void saveResults() {
        StringBuilder sb = new StringBuilder();
        for (String setting : resultMap.keySet()) {
            for (Integer id : resultMap.get(setting).keySet()) {
                sb.append(getSource()).append(",").append(setting).append(",").append(id).append(",")
                        .append(startTS).append(",").append(getXpDuration());
                for (String key : resultMap.get(setting).get(id).keySet()) {
                    sb.append(",").append(resultMap.get(setting).get(id).get(key));
                }
                sb.append("\n");
            }
        }
        FileHelper.log(sb.toString(), resultFileName);
    }

    private String getCurrentSettingName() {
        return settings.get(settingIndex).asObject().get("settingName").asString();
    }

    protected final long getXpDuration() {
        return Convert.strDurationToMillisec(nbDays + "d");
    }

    protected void chartSchedule(final Schedule schedule,
                                 final String name) {
        StringBuilder sbFunction = new StringBuilder();
        StringBuilder sbHtml = new StringBuilder();

        int i = 0;
        for (String metric : schedule.getMetricRecordMap().keySet()) {
            MetricRecord mr = schedule.getMetricRecordMap().get(metric);
            if (mr.getRecords()!=null) {
                String chartType = "visualization.AreaChart";
                if (mr.getRecords().getFirst() instanceof SampledRecord) {
                    chartType = "charts.Bar";
                }
                String options = "title: '" + metric + "', legend: {position: 'none'}, bar:{groupWidth: '100%'}";
                StringBuilder sbData = new StringBuilder();
                sbData.append("['Time','").append(metric).append("']");
                for (Record record : mr.getRecords()) {
                    sbData.append(",[");
                    sbData.append("new Date(").append(record.getTS() + mr.getStartTime()).append("),");
                    sbData.append(record.getValue()).append("]");
                }
                sbData.append(",[new Date(").append(schedule.getHorizon() + mr.getStartTime())
                        .append("),  ").append(mr.getLastValue()).append("]");

                sbFunction.append("            var data").append(i).append(" = google.visualization.arrayToDataTable([").append(sbData.toString()).append("]);\n")
                        .append("            var options").append(i).append(" = {").append(options).append("};\n")
                        .append("            var chart").append(i).append(" = new google.").append(chartType).append("(document.getElementById('").append(metric).append("'));\n")
                        .append("            chart").append(i).append(".draw(data").append(i).append(", options").append(i).append(");\n");

                sbHtml.append("<div id=\"").append(metric).append("\" style=\"width: ").append(1500 * getXpDuration() / experimenter.DAY).append("px; height: 300px\"></div>\n");
            }

            i++;
        }

        FileHelper.save(coreHtml().replace("${title}", name).replace("${html}", sbHtml)
                .replace("${func}", sbFunction), name + ".html");
    }

    private String coreHtml() {
        return "<html>\n<head><title>${title}</title>" +
                "    <script type=\"text/javascript\" src=\"https://www.google.com/jsapi?autoload={'modules':[{'name':'visualization','version':'1','packages':['corechart','bar']}]}\"></script>\n" +
                "    <script type=\"text/javascript\">\n" +
                "        google.setOnLoadCallback(drawChart);\n" +
                "        function drawChart() {\n${func}\n}\n" +
                "    </script>\n" +
                "</head>\n" +
                "<body>\n${html}</body></html>";
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

        Request triggerReq = new Request(experimenter.getFullId(), experimenter.getNode() + ".context",
                experimenter.getCurrentTime(), "addTriggers",
                new Object[]{new Trigger[]{ctrlGen, bgTrigger, interTrigger, consTrigger, genTrigger,
                        balanceTrigger, importTrigger, exportTrigger}});
        experimenter.sendRequest(triggerReq, new ShowIfErrorCallback());
    }

    public void log(String log) {
        Log.info("[XP " + src + "-" + getCurrentSettingName() + ", iteration: "+ iterationId +"/"+iteration
                + ", nbDays: " + nbDays + ", startTS" + experimenter.strLocalTime(startTS) + "] " + log);
    }

    public int getZip() {
        return zip;
    }

    public JsonObject getProperties() {
        return properties;
    }

    public TreeMap<String, TreeMap<Integer, TreeMap<String, String>>> getResultMap() {
        return resultMap;
    }

    public JsonObject getCurrentSetting() {
        return settings.get(settingIndex).asObject();
    }
}
