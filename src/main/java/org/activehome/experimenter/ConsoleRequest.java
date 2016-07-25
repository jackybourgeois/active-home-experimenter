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


import org.activehome.com.RequestCallback;
import org.activehome.com.error.ErrorType;
import org.activehome.com.error.Error;
import org.activehome.context.data.UserInfo;

import java.util.HashMap;
import java.util.LinkedList;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class ConsoleRequest {

    /**
     * Initialize a xp
     */
        public void initXp(final LinkedList<String> params,
                       final UserInfo userInfo,
                       final RequestCallback callback) {
        HashMap<String, Object> paramsMap = new HashMap<>();
        paramsMap.put("start", null);
        paramsMap.put("end", null);
        paramsMap.put("zip", "x300");
        paramsMap.put("src", null);
        paramsMap.put("adaptation", "");
        paramsMap.put("horizon", "1d");
        paramsMap.put("granularity", "1h");
//        paramsMap.put("objectives", "MinimizeUserDisruption,MinimizeUserCosts,MinimizeEnvironmentalImpact");
        paramsMap.put("objectives", "MinimizeEnvironmentalImpact");
        paramsMap.put("forecaster", "org.activehome.prediction.Eforecaster");
        for (String param : params) {
            String[] paramParts = param.split("=");
            String val = paramParts[1];
            if ((val.startsWith("\"") && val.endsWith("\""))
                    || ((val.startsWith("'") && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }
            if (paramParts.length == 2) paramsMap.put(paramParts[0], val);
        }

        boolean allRequirements = true;
        for (String param : paramsMap.keySet()) {
            if (paramsMap.get(param) == null) {
                allRequirements = false;
                break;
            }
        }

        if (allRequirements) {
//            initXp(paramsMap, userInfo, callback);
        } else {
            callback.error(new Error(ErrorType.WRONG_PARAM,
                    "Missing required params start, end or src."));
        }

    }

    public void start() {

    }

    public void stop() {

    }

//    case "init":
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

}
