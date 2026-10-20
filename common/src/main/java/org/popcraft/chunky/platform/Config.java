package org.popcraft.chunky.platform;

import java.nio.file.Path;

public interface Config {
    Path getDirectory();

    int getVersion();

    String getLanguage();

    boolean getContinueOnRestart();

    boolean isForceLoadExistingChunks();

    boolean isSilent();

    void setSilent(boolean silent);

    int getUpdateInterval();

    void setUpdateInterval(int updateInterval);

    void reload();

    /**
     * Maximo de chunks en carga simultanea. {@code 0} o menos significa automatico.
     */
    default int getMaxWorkingCount() {
        return 0;
    }

    /**
     * Consultar al servidor si cada chunk ya existe antes de generarlo.
     */
    default boolean isPrecheckExistingChunks() {
        return false;
    }

    /**
     * Paper: cargar chunks con maxima prioridad.
     */
    default boolean isUrgentLoad() {
        return false;
    }

    /**
     * Paper: tratar los chunks desactualizados (OUTDATED) como ya generados.
     */
    default boolean isLoadOutdatedChunks() {
        return false;
    }

    /**
     * Mantener cada chunk cargado (ticking) durante N segundos despues de generarlo. {@code 0} lo desactiva.
     */
    default int getTickingLoadDuration() {
        return 0;
    }

    /**
     * Esperar a que el ticket de chunk se retire antes de continuar con el siguiente.
     */
    default boolean isAwaitTicketRemoval() {
        return false;
    }

    /**
     * Hilos dedicados a lanzar las peticiones de carga en paralelo (multi-tarea).
     * {@code 0} o menos significa automatico.
     */
    default int getLoadThreads() {
        return 0;
    }
}
