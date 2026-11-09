package testigo.maestro

/** Un `commands.json` con la forma que escribe Maestro 2.11.0 (`--test-output-dir`), con horas simples. */
internal object CommandsFixture {
    const val JSON = """
[
 {"command":{"defineVariablesCommand":{"env":{"X":"1"},"optional":false}},"metadata":{"status":"COMPLETED","timestamp":1000,"duration":2,"sequenceNumber":0,"depth":0}},
 {"command":{"applyConfigurationCommand":{"config":{"appId":"com.empresa.app"},"optional":false}},"metadata":{"status":"COMPLETED","timestamp":1002,"duration":0,"sequenceNumber":1,"depth":0}},
 {"command":{"launchAppCommand":{"appId":"com.empresa.app","optional":false}},"metadata":{"status":"COMPLETED","timestamp":1003,"duration":2000,"sequenceNumber":2,"depth":0}},
 {"command":{"runFlowCommand":{"commands":[],"sourceDescription":"/flows/subflows/login.yaml","label":"subflow_login","optional":false}},"metadata":{"status":"COMPLETED","timestamp":3003,"duration":40000,"sequenceNumber":3,"depth":0}},
 {"command":{"inputTextCommand":{"text":"Secreto123","optional":false}},"metadata":{"status":"COMPLETED","timestamp":3010,"duration":300,"sequenceNumber":4,"depth":1}},
 {"command":{"tapOnElement":{"selector":{"textRegex":"Ingresar"},"label":"confirmar","optional":false}},"metadata":{"status":"COMPLETED","timestamp":3400,"duration":8000,"sequenceNumber":5,"depth":1}},
 {"command":{"backPressCommand":{"optional":false}},"metadata":{"status":"COMPLETED","timestamp":11400,"duration":12000,"sequenceNumber":6,"depth":1}},
 {"command":{"assertConditionCommand":{"condition":{"visible":{"idRegex":"home_root"}},"timeout":"20000","optional":false}},"metadata":{"status":"COMPLETED","timestamp":43003,"duration":200,"sequenceNumber":7,"depth":0}},
 {"command":{"assertConditionCommand":{"condition":{"notVisible":{"textRegex":"Cargando"}},"label":"sin_spinner","optional":false}},"metadata":{"status":"FAILED","timestamp":43203,"duration":50,"sequenceNumber":8,"depth":0}}
]
"""
}
