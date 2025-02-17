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
package org.neo4j.bolt.v44;

import java.io.IOException;

import org.neo4j.bolt.messaging.BoltRequestMessageWriter;
import org.neo4j.bolt.messaging.RecordingByteChannel;
import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.packstream.BufferedChannelOutput;
import org.neo4j.bolt.packstream.Neo4jPack;

public class BoltProtocolV44ComponentFactory
{

    public static BoltRequestMessageWriter requestMessageWriter( Neo4jPack.Packer packer )
    {
        return new BoltRequestMessageWriterV44( packer );
    }

    public static byte[] encode( Neo4jPack neo4jPack, RequestMessage... messages ) throws IOException
    {
        RecordingByteChannel rawData = new RecordingByteChannel();
        Neo4jPack.Packer packer = neo4jPack.newPacker( new BufferedChannelOutput( rawData ) );
        BoltRequestMessageWriter writer = requestMessageWriter( packer );

        for ( RequestMessage message : messages )
        {
            writer.write( message );
        }
        writer.flush();

        return rawData.getBytes();
    }
}
