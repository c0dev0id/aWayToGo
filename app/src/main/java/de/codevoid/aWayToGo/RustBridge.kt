package de.codevoid.aWayToGo

object RustBridge {
    init {
        System.loadLibrary("awaytogo")
    }

    external fun greetFromRust(): String
}
