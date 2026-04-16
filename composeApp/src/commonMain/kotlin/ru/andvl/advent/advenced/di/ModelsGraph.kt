package ru.andvl.advent.advenced.di

import ru.andvl.advent.advenced.data.remote.createHttpClient
import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterApi
import ru.andvl.advent.advenced.data.repository.ModelsRepositoryImpl
import ru.andvl.advent.advenced.domain.usecase.GetModelDetailsUseCase
import ru.andvl.advent.advenced.domain.usecase.GetTopModelsUseCase

object ModelsGraph {
    private val repository by lazy {
        val client = createHttpClient()
        val api = OpenRouterApi(client)
        ModelsRepositoryImpl(api)
    }

    val getTopModels: GetTopModelsUseCase by lazy { GetTopModelsUseCase(repository) }
    val getModelDetails: GetModelDetailsUseCase by lazy { GetModelDetailsUseCase(repository) }
}
