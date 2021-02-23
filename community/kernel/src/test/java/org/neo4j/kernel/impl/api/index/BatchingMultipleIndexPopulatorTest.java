/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.neo4j.common.EntityType;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.kernel.api.PopulationProgress;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.api.exceptions.index.IndexPopulationFailedKernelException;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.schema.index.TestIndexDescriptorFactory;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.transaction.state.storeview.NeoStoreIndexStoreView;
import org.neo4j.lock.LockService;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.scheduler.JobSchedulerExtension;
import org.neo4j.storageengine.api.EntityUpdates;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.NodePropertyAccessor;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.ValueIndexEntryUpdate;
import org.neo4j.test.InMemoryTokens;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.scheduler.CallingThreadJobScheduler;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.values.storable.Values;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.common.Subject.AUTH_DISABLED;
import static org.neo4j.io.pagecache.tracing.PageCacheTracer.NULL;
import static org.neo4j.kernel.api.index.IndexQueryHelper.add;
import static org.neo4j.kernel.impl.api.index.IndexPopulationFailure.failure;
import static org.neo4j.kernel.impl.api.index.StoreScan.NO_EXTERNAL_UPDATES;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

@ExtendWith( JobSchedulerExtension.class )
public class BatchingMultipleIndexPopulatorTest
{
    private static final int propertyId = 1;
    private static final int labelId = 1;

    @Inject
    private JobScheduler jobScheduler;

    private final IndexDescriptor index1 = TestIndexDescriptorFactory.forLabel( 1, 1 );
    private final IndexDescriptor index42 = TestIndexDescriptorFactory.forLabel( 42, 42 );
    private final InMemoryTokens tokens = new InMemoryTokens();

    @Test
    void populateFromQueueDoesNothingIfThresholdNotReached() throws Exception
    {
        MultipleIndexPopulator batchingPopulator = new MultipleIndexPopulator(
                mock( IndexStoreView.class ), NullLogProvider.getInstance(), EntityType.NODE,
                mock( SchemaState.class ), mock( IndexStatisticsStore.class ), new CallingThreadJobScheduler(), tokens, NULL, INSTANCE, "", AUTH_DISABLED,
                Config.defaults( GraphDatabaseInternalSettings.index_population_queue_threshold, 5 ) );

        IndexPopulator populator = addPopulator( batchingPopulator, index1 );
        IndexUpdater updater = mock( IndexUpdater.class );
        when( populator.newPopulatingUpdater( any(), any() ) ).thenReturn( updater );

        IndexEntryUpdate<?> update1 = add( 1, index1.schema(), "foo" );
        IndexEntryUpdate<?> update2 = add( 2, index1.schema(), "bar" );
        batchingPopulator.queueConcurrentUpdate( update1 );
        batchingPopulator.queueConcurrentUpdate( update2 );

        assertThat( batchingPopulator.needToApplyExternalUpdates() ).isFalse();

        verify( updater, never() ).process( any( ValueIndexEntryUpdate.class ) );
        verify( populator, never() ).newPopulatingUpdater( any(), any() );
    }

    @Test
    void populateFromQueuePopulatesWhenThresholdReached() throws Exception
    {
        NeoStoreIndexStoreView storeView =
                new NeoStoreIndexStoreView( LockService.NO_LOCK_SERVICE, () -> mock( StorageReader.class ), Config.defaults(), jobScheduler );
        MultipleIndexPopulator batchingPopulator = new MultipleIndexPopulator(
                storeView, NullLogProvider.getInstance(), EntityType.NODE, mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler(), tokens, NULL, INSTANCE, "", AUTH_DISABLED,
                Config.defaults( GraphDatabaseInternalSettings.index_population_queue_threshold, 2 ) );

        IndexPopulator populator1 = addPopulator( batchingPopulator, index1 );
        IndexUpdater updater1 = mock( IndexUpdater.class );
        when( populator1.newPopulatingUpdater( any(), any() ) ).thenReturn( updater1 );

        IndexPopulator populator2 = addPopulator( batchingPopulator, index42 );
        IndexUpdater updater2 = mock( IndexUpdater.class );
        when( populator2.newPopulatingUpdater( any(), any() ) ).thenReturn( updater2 );

        batchingPopulator.createStoreScan( NULL );
        IndexEntryUpdate<?> update1 = add( 1, index1.schema(), "foo" );
        IndexEntryUpdate<?> update2 = add( 2, index42.schema(), "bar" );
        IndexEntryUpdate<?> update3 = add( 3, index1.schema(), "baz" );
        batchingPopulator.queueConcurrentUpdate( update1 );
        batchingPopulator.queueConcurrentUpdate( update2 );
        batchingPopulator.queueConcurrentUpdate( update3 );

        batchingPopulator.applyExternalUpdates( 42 );

        verify( updater1 ).process( update1 );
        verify( updater1 ).process( update3 );
        verify( updater2 ).process( update2 );
    }

    @Test
    void pendingBatchesFlushedAfterStoreScan() throws Exception
    {
        EntityUpdates update1 = nodeUpdates( 1, propertyId, "foo", labelId );
        EntityUpdates update2 = nodeUpdates( 2, propertyId, "bar", labelId );
        EntityUpdates update3 = nodeUpdates( 3, propertyId, "baz", labelId );
        EntityUpdates update42 = nodeUpdates( 4, 42, "42", 42 );
        IndexStoreView storeView = newStoreView( update1, update2, update3, update42 );

        MultipleIndexPopulator batchingPopulator = new MultipleIndexPopulator( storeView,
                NullLogProvider.getInstance(), EntityType.NODE, mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler(), tokens, NULL, INSTANCE, "", AUTH_DISABLED, Config.defaults() );

        IndexPopulator populator1 = addPopulator( batchingPopulator, index1 );
        IndexPopulator populator42 = addPopulator( batchingPopulator, index42 );

        batchingPopulator.createStoreScan( NULL ).run( NO_EXTERNAL_UPDATES );

        verify( populator1 ).add( forUpdates( index1, update1, update2, update3 ), PageCursorTracer.NULL );
        verify( populator42 ).add( forUpdates( index42, update42 ), PageCursorTracer.NULL );
    }

    @Test
    void populatorMarkedAsFailed() throws Exception
    {
        EntityUpdates update1 = nodeUpdates( 1, propertyId, "aaa", labelId );
        EntityUpdates update2 = nodeUpdates( 1, propertyId, "bbb", labelId );
        IndexStoreView storeView = newStoreView( update1, update2 );

        RuntimeException batchFlushError = new RuntimeException( "Batch failed" );

        IndexPopulator populator;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ThreadPoolJobScheduler jobScheduler = new ThreadPoolJobScheduler( executor );
        try
        {
            MultipleIndexPopulator batchingPopulator = new MultipleIndexPopulator( storeView,
                    NullLogProvider.getInstance(), EntityType.NODE, mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                    jobScheduler, tokens, NULL, INSTANCE,  "", AUTH_DISABLED,
                    Config.defaults( GraphDatabaseInternalSettings.index_population_batch_max_byte_size, 1L ) );

            populator = addPopulator( batchingPopulator, index1 );
            List<IndexEntryUpdate<IndexDescriptor>> expected = forUpdates( index1, update1, update2 );
            doThrow( batchFlushError ).when( populator ).add( expected, PageCursorTracer.NULL );

            batchingPopulator.createStoreScan( NULL ).run( NO_EXTERNAL_UPDATES );
        }
        finally
        {
            jobScheduler.shutdown();
            executor.awaitTermination( 1, TimeUnit.MINUTES );
        }

        verify( populator ).markAsFailed( failure( batchFlushError ).asString() );
    }

    private List<IndexEntryUpdate<IndexDescriptor>> forUpdates( IndexDescriptor index, EntityUpdates... updates )
    {
        return Iterables.asList(
                Iterables.concat(
                        Iterables.map(
                                update -> update.valueUpdatesForIndexKeys( Iterables.asIterable( index ) ),
                                Arrays.asList( updates )
                        ) ) );
    }

    private EntityUpdates nodeUpdates( int nodeId, int propertyId, String propertyValue, long...
            labelIds )
    {
        return EntityUpdates.forEntity( nodeId, false ).withTokens( labelIds ).withTokensAfter( labelIds )
                .added( propertyId, Values.of( propertyValue ) )
                .build();
    }

    private static IndexPopulator addPopulator( MultipleIndexPopulator batchingPopulator, IndexDescriptor descriptor )
    {
        IndexPopulator populator = mock( IndexPopulator.class );

        IndexProxyFactory indexProxyFactory = mock( IndexProxyFactory.class );
        FailedIndexProxyFactory failedIndexProxyFactory = mock( FailedIndexProxyFactory.class );
        FlippableIndexProxy flipper = new FlippableIndexProxy();
        flipper.setFlipTarget( indexProxyFactory );

        batchingPopulator.addPopulator( populator,
                                        descriptor,
                                        flipper,
                                        failedIndexProxyFactory, "testIndex" );

        return populator;
    }

    private static IndexStoreView newStoreView( EntityUpdates... updates )
    {
        IndexStoreView storeView = mock( IndexStoreView.class );
        when( storeView.visitNodes( any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any() ) ).thenAnswer( invocation ->
        {
            Visitor<List<EntityUpdates>,IndexPopulationFailedKernelException> visitorArg = invocation.getArgument( 2 );
            return new IndexEntryUpdateScan( updates, visitorArg );
        } );
        when( storeView.newPropertyAccessor( any( PageCursorTracer.class ), any() ) ).thenReturn( mock( NodePropertyAccessor.class ) );
        return storeView;
    }

    private static class IndexEntryUpdateScan implements StoreScan<IndexPopulationFailedKernelException>
    {
        final EntityUpdates[] updates;
        final Visitor<List<EntityUpdates>,IndexPopulationFailedKernelException> visitor;

        boolean stop;

        IndexEntryUpdateScan( EntityUpdates[] updates,
                Visitor<List<EntityUpdates>,IndexPopulationFailedKernelException> visitor )
        {
            this.updates = updates;
            this.visitor = visitor;
        }

        @Override
        public void run( ExternalUpdatesCheck externalUpdatesCheck ) throws IndexPopulationFailedKernelException
        {
            if ( stop )
            {
                return;
            }
            visitor.visit( List.of( updates ) );
        }

        @Override
        public void stop()
        {
            stop = true;
        }

        @Override
        public PopulationProgress getProgress()
        {
            return PopulationProgress.NONE;
        }
    }
}
