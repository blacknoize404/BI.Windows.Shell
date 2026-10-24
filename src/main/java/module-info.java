/**
 * Integración con la shell de Windows desde Java, sin código nativo propio: usa la API de funciones
 * externas (FFM) de Java 25. Necesita {@code --enable-native-access=bi.windows.shell} (o {@code ALL-UNNAMED}).
 */
module bi.windows.shell {
    exports bi.windows.shell;
}
