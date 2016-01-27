# Experimenter

The Experimenter transforms Active Home into a simulator, allowing to run, replicate and analyse multiple settings.

## Experimenter web interface

## Settings

### General Settings

```javascript
"horizon": "1d",
"granularity": "1h",
"iteration": "1",
"zip": "1800"

"resultFileName": "results.csv"

### Xp settings

#### Actual


#### Reactive

```javascript
{
      "settingName": "actual",
      "reactive": false,
      "predictive": false,
      "collab": false
}
```

#### Predictive

```javascript
{
      "settingName": "reactive",
      "reactive": true,
      "predictive": false,
      "collab": false
}
```

#### Reactive / Predictive


#### Reactive / Predictive Collab



### Evaluators

Specify the list of evaluators to run during the experiment.

* ![Energy](https://github.com/jackybourgeois/activehome-energy/blob/master/org.activehome.energy.evaluator.energy/docs/energyEvaluator.md)
* ![Cost](https://github.com/jackybourgeois/activehome-energy/blob/master/org.activehome.energy.evaluator.cost/docs/costEvaluator.md)
* ![Environmental Impact](https://github.com/jackybourgeois/activehome-energy/blob/master/org.activehome.energy.evaluator.co2/docs/environmentalImpactEvaluator.md)

```javascripts
"evaluators": [
    "org.activehome.energy.evaluator.energy.EnergyEvaluator",
    ...
  ]
```

### External input

#### Fiscal meters

* ![EConstantFM](https://github.com/jackybourgeois/activehome-energy/blob/master/org.activehome.energy.io.emulator/docs/eConstantFM.md)
* ![EDualRateFM](https://github.com/jackybourgeois/activehome-energy/blob/master/org.activehome.energy.io.emulator/docs/eDualRateFM.md)

```javascripts
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
```

#### Grid


```javascript
"grid": {
    "urlSQLSource": "jdbc:mysql://localhost:3306/grid?user=demo&password=demo",
    "tableName": "uk_grid"
  }
```
  
### Objectives


```javascript
"objectives": [
    "org.activehome.objective.MinimizeExport",
    ...
  ]
```
  
### Predictors

```javascript
"predictors": [
    "org.activehome.energy.predictor.emulator.EApplianceUsagePredictor",
    ...
  ]
```

### Sources

```javascript
"sources": {
    "household_x": [
      {
        "start": 1367020800000,
        "nbDays": 4
      },
      ...
    ]
  }
```