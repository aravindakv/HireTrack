plugins {
    alias(libs.plugins.versions)
}

tasks.register("printModules") {
    doLast { subprojects.forEach { println(it.path) } }
}