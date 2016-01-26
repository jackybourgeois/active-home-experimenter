# activehome-experimenter

The Experimenter transforms Active Home into a simulator,, allowing to run, replicate and analyse multiple settings.

## Experimenter web interface

## Settings

### General Settings

"horizon": "1d"

"granularity": "1h"

"iteration": "1"

"zip": "1800"

"resultFileName": "results.csv"

### Xp settings

"settings": [
    {
      "settingName": "actual",
      "reactive": false,
      "predictive": false,
      "collab": false
    },
    {
      "settingName": "reactive",
      "reactive": true,
      "predictive": false,
      "collab": false
    },
    ...
  ],

### Evaluators


"evaluators": [
    "org.activehome.energy.evaluator.energy.EnergyEvaluator",
    ...
  ]

### External input

Fiscal meters

"fiscalmeters": [
    {
      "name": "importFM",
      "type": "org.activehome.energy.io.emulator.fiscalmeter.EDualRateFM",
      "metricId": "tariff.elec.import",
      "highRate": "0.15771",
      "lowRate": "0.06615",
      "switchTime": "7h"
    },
    ...
  ]


Grid

"grid": {
    "urlSQLSource": "jdbc:mysql://localhost:3306/grid?user=demo&password=demo",
    "tableName": "uk_grid"
  }
  
### Objectives

"objectives": [
    "org.activehome.objective.MinimizeExport",
    ...
  ]
  
### Predictors

"predictors": [
    "org.activehome.energy.predictor.emulator.EApplianceUsagePredictor",
    ...
  ]

### Sources

"sources": {
    "household_x": [
      {
        "start": 1367020800000,
        "nbDays": 4
      },
      ...
    ]
  }
