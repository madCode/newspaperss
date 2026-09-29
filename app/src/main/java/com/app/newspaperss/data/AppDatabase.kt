package com.app.newspaperss.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

class Converters {
    @TypeConverter fun instantToLong(i: Instant?): Long? = i?.toEpochMilli()
    @TypeConverter fun longToInstant(l: Long?): Instant? = l?.let(Instant::ofEpochMilli)
}

@Database(
    entities = [SourceEntity::class, ArticleEntity::class, EditionEntity::class, EditionArticleEntity::class, DeliveredUrlEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun articles(): ArticleDao
    abstract fun editions(): EditionDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "newspaperss.db").build()
    }
}
