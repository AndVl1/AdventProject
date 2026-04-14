package ru.andvl.advent.advenced.di

import ru.andvl.advent.advenced.data.remote.createHttpClient
import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterApi
import ru.andvl.advent.advenced.data.repository.ModelsRepositoryImpl
import ru.andvl.advent.advenced.domain.usecase.GetTopModelsUseCase

object ModelsGraph {
    val getTopModels: GetTopModelsUseCase by lazy {
        val client = createHttpClient()
        val api = OpenRouterApi(client)
        val repository = ModelsRepositoryImpl(api)
        GetTopModelsUseCase(repository)
    }
}
