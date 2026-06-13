package app.lawnchair.data.widget

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetPaddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WidgetPadding)

    @Query("DELETE FROM widgetpadding WHERE appWidgetId = :appWidgetId")
    suspend fun delete(appWidgetId: Int)

    @Query("SELECT * FROM widgetpadding")
    fun observeAll(): Flow<List<WidgetPadding>>

    @Query("DELETE FROM widgetpadding")
    suspend fun deleteAll()

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
