package bi.windows.shell;

/**
 * Cómo quedó la aplicación del modo oscuro: medible, no prometida.
 *
 * @param applied      si Windows aceptó el cambio
 * @param previousMode el modo que había antes (0 por defecto, 1 permitir oscuro, 2 forzar oscuro, 3 forzar claro)
 * @param ordinalUsed  el ordinal de {@code uxtheme.dll} que se llamó, 0 si ninguno
 * @param error        por qué no se aplicó, o {@code null}
 */
public record DarkModeResult(boolean applied, int previousMode, int ordinalUsed, String error) {
}
