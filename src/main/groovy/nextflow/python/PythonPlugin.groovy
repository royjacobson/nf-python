package nextflow.python

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import nextflow.plugin.Scoped
import org.pf4j.PluginWrapper

@CompileStatic
class PythonPlugin extends BasePlugin {

    PythonPlugin(PluginWrapper wrapper) {
        super(wrapper)
        PythonExtension.setPluginDir(getWrapper().getPluginPath())
    }
}
