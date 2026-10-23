package com.vic.inkflow

import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.JdbcDocumentRepository
import com.vic.inkflow.data.JdbcFolderRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin module for the desktop app (Milestone 6).
 *
 * ## Scope
 *
 * Provides the shared data sources (DatabaseManager + the two repository
 * wrappers). ViewModels are still created with `remember { ... }` in the
 * composables because they need a `CoroutineScope` tied to the composition's
 * lifecycle; passing that scope through Koin would require a factory parameter
 * and would obscure the lifecycle ownership. The repositories, by contrast, are
 * long-lived and benefit from being singletons resolved via `koinInject()`.
 */
val appModule = module {
    single { DatabaseManager(AppPaths.dbPath).also { it.connect() } }

    single { JdbcDocumentRepository(get()) }
    single { JdbcFolderRepository(get()) }
}
