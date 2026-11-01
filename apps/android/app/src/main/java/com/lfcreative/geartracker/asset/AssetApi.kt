package com.lfcreative.geartracker.asset

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Online operations on assets. An interface so ViewModels are tested with fakes. */
interface AssetApi {
    suspend fun createAsset(request: JsonObject, scanned: String?): CreateResult
    suspend fun search(query: String): Result<List<AssetSummary>>
    suspend fun detail(id: String): Result<AssetDetail?>
    suspend fun categories(): Result<List<CategoryInfo>>
    suspend fun templates(): Result<List<TemplateInfo>>
}

@Singleton
class SupabaseAssetApi @Inject constructor(private val client: SupabaseClient) : AssetApi {

    override suspend fun createAsset(request: JsonObject, scanned: String?): CreateResult = try {
        AssetJson.createResult(scanned, client.postgrest.rpc("create_asset", buildJsonObject { put("p", request) }).decodeAs<JsonObject>())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        CreateResult.Rejected(e.description ?: e.error)
    } catch (e: Exception) {
        CreateResult.Offline("No connection. Nothing was created; try again.")
    }

    override suspend fun search(query: String): Result<List<AssetSummary>> = call {
        AssetJson.summaries(client.postgrest.rpc("search_assets", buildJsonObject { put("p_query", JsonPrimitive(query)) }).decodeAs<JsonArray>())
    }

    override suspend fun detail(id: String): Result<AssetDetail?> = call {
        AssetJson.detail(client.postgrest.rpc("get_asset_detail", buildJsonObject { put("p_asset", JsonPrimitive(id)) }).decodeAs<JsonObject>())
    }

    override suspend fun categories(): Result<List<CategoryInfo>> = call {
        AssetJson.categories(client.postgrest.from("categories").select().decodeAs<JsonArray>()).sortedBy { it.name }
    }

    override suspend fun templates(): Result<List<TemplateInfo>> = call {
        AssetJson.templates(client.postgrest.from("asset_templates").select().decodeAs<JsonArray>()).sortedBy { it.name }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
