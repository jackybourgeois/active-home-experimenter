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


import org.activehome.context.data.MetricRecord;
import org.activehome.context.data.Record;
import org.activehome.context.data.SampledRecord;
import org.activehome.context.data.Schedule;
import org.activehome.time.TimeControlled;
import org.activehome.tools.file.FileHelper;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class ResultChart {

    public static void chartSchedule(final Schedule schedule,
                                     final String folder,
                                     final String name) {
        StringBuilder sbFunction = new StringBuilder();
        StringBuilder sbHtml = new StringBuilder();

        int i = 0;
        for (String metric : schedule.getMetricRecordMap().keySet()) {
            MetricRecord mr = schedule.getMetricRecordMap().get(metric);
            if (mr.getRecords() != null) {
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

                sbHtml.append("<div id=\"").append(metric).append("\" style=\"width: ").append(1500 * schedule.getHorizon() / TimeControlled.DAY).append("px; height: 300px\"></div>\n");
            }

            i++;
        }

        FileHelper.save(coreHtml().replace("${title}", name).replace("${html}", sbHtml)
                .replace("${func}", sbFunction), folder + "/" + name + ".html");
    }

    public static String coreHtml() {
        return "<html>\n<head><title>${title}</title>" +
                "    <script type=\"text/javascript\" src=\"https://www.google.com/jsapi?autoload={'modules':[{'name':'visualization','version':'1','packages':['corechart','bar']}]}\"></script>\n" +
                "    <script type=\"text/javascript\">\n" +
                "        google.setOnLoadCallback(drawChart);\n" +
                "        function drawChart() {\n${func}\n}\n" +
                "    </script>\n" +
                "</head>\n" +
                "<body>\n${html}</body></html>";
    }


}
