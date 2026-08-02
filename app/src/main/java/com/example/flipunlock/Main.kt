package com.example.flipunlock

import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.DisplayState
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting")
        DisplayState.hook(param)
        AppRestriction.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName}")
        // Hook files dispatch here as they are implemented.
    }
}
