package com.rkophs.mill
import mill.*, javalib.*
import mill.api.{BuildCtx, Module, PrecompiledModule}

class SimpleJavaModule(val scriptConfig: PrecompiledModule.Config)
    extends JavaModule with PrecompiledModule {

  override lazy val millDiscover = mill.api.Discover[this.type]

  lazy val allModuleSegments =
    BuildCtx.rootModule
      .asInstanceOf[Module]
      .moduleInternal
      .modules
      .map(_.moduleSegments)

  def listModules = Task { allModuleSegments.map(_.render) }
}
