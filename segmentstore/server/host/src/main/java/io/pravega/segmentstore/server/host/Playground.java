/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.host;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.pravega.common.ObjectBuilder;
import io.pravega.common.Timer;
import io.pravega.common.io.FixedByteArrayOutputStream;
import io.pravega.common.io.serialization.FormatDescriptor;
import io.pravega.common.io.serialization.RevisionDataInput;
import io.pravega.common.io.serialization.RevisionDataOutput;
import io.pravega.common.io.serialization.VersionedSerializer;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Cleanup;
import lombok.Getter;
import lombok.Setter;
import lombok.Singular;
import lombok.val;
import org.slf4j.LoggerFactory;

/**
 * Playground Test class.
 */
public class Playground {

    public static void main(String[] args) throws Exception {

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLoggerList().get(0).setLevel(Level.INFO);
        //context.reset();
        testSerializer();
        //testSerializerPerf();
    }

    private static void testSerializerPerf() throws IOException {
        val mc = MyClass.builder()
                        .name("name")
                        .id((long) Integer.MAX_VALUE + 123)
                        .nestedClass(new MyNestedClass("myNestedClass", "name2"))
                        .tick(12345)
                        .isTrue(true)
                        .build();
        int count = 10000000;
        byte[] buffer = new byte[32 * 1024];
        val s = VersionedSerializer.use(new MyClassFormat1());
        Timer t2 = new Timer();
        serializeUsingSerializer(mc, s, buffer, count);
        long sElapsed = t2.getElapsedNanos();

        System.gc();
        Timer t1 = new Timer();
        serializeUsingDataOutput(mc, buffer, count);
        long dosElapsed = t1.getElapsedNanos();

        System.out.println(String.format("DOS = %s ms, S = %s ms", dosElapsed / 1000000, sElapsed / 1000000));
    }

    private static void serializeUsingSerializer(MyClass c, VersionedSerializer<MyClass, MyClass.MyClassBuilder> versionedSerializer, byte[] buffer, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            versionedSerializer.serialize(new FixedByteArrayOutputStream(buffer, 0, buffer.length), c);
        }
    }

    private static void serializeUsingDataOutput(MyClass c, byte[] buffer, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            val dos = new DataOutputStream(new FixedByteArrayOutputStream(buffer, 0, buffer.length));
            dos.writeUTF(c.getName());
            dos.writeLong(c.getId());
            dos.writeInt(c.getTick());
            dos.writeBoolean(c.isTrue());
            dos.writeUTF(c.getNestedClass().name);
            dos.writeUTF(c.getNestedClass().name2);
        }
    }

    private static void testSerializer() {
        val mc1 = MyClass.builder()
                         .name("name")
                         .id((long) Integer.MAX_VALUE + 123)
                         .nestedClass(new MyNestedClass("myNestedClass", "myNestedClassName2"))
                         .tick(12345)
                         .isTrue(true)
                         .sl("a")
                         .sl("b")
                         .nc(new MyNestedClass("nc1", "nc12"))
                         .nc(new MyNestedClass("nc2", "nc22"))
                         .build();
        System.out.println("Initial: " + mc1.toString());

        val descriptors = new HashMap<String, FormatDescriptor.WithBuilder<MyClass, MyClass.MyClassBuilder>>();
        descriptors.put("0.2", new MyClassFormat0());
        descriptors.put("1.0", new MyClassFormat1());

        for (val s : descriptors.entrySet()) {
            for (val d : descriptors.entrySet()) {
                System.out.print(String.format("S (%s) -> D(%s): ", s.getKey(), d.getKey()));
                try {
                    val data = new byte[1024];
                    @Cleanup
                    val stream = new FixedByteArrayOutputStream(data, 0, data.length);
                    val serializer = VersionedSerializer.use(s.getValue());
                    serializer.serialize(stream, mc1);
                    stream.flush();
                    val deserializer = VersionedSerializer.use(d.getValue());
                    val mc2 = deserializer.deserialize(new ByteArrayInputStream(data));
                    System.out.println(mc2);
                } catch (Exception ex) {
                    System.out.println("ERROR");
                    ex.printStackTrace(System.out);
                }
            }
        }
    }

    //region Class (With Builder) and its custom Serializers

    @Builder
    @Getter
    @Setter
    private static class MyClass {
        private String name;
        private long id;
        private MyNestedClass nestedClass;
        private int tick;
        private boolean isTrue;
        @Singular(value = "sl")
        private List<String> stringList;
        @Singular(value = "nc")
        private List<MyNestedClass> nestedClasses;

        @Override
        public String toString() {
            return String.format("N=%s, I=%d, T=%d, B=%s NC=%s, SL={%s}, NCL={%s}",
                    this.name, this.id, this.tick, this.isTrue, this.nestedClass,
                    String.join(",", this.stringList),
                    this.nestedClasses.stream().map(Object::toString).collect(Collectors.joining(",")));
        }

        static class MyClassBuilder implements ObjectBuilder<MyClass> {
        }
    }

    @Builder
    private static class MyNestedClass {
        private final String name;
        private final String name2;

        @Override
        public String toString() {
            return "N1=" + this.name + ", N2=" + this.name2;
        }

        static class MyNestedClassBuilder implements ObjectBuilder<MyNestedClass> {
        }
    }

    private static class MyClassFormat0 extends FormatDescriptor.WithBuilder<MyClass, MyClass.MyClassBuilder> {
        private final VersionedSerializer.WithBuilder<MyNestedClass, MyNestedClass.MyNestedClassBuilder> ncs00 = VersionedSerializer.use(new MyNestedClassFormat00());
        private final VersionedSerializer.WithBuilder<MyNestedClass, MyNestedClass.MyNestedClassBuilder> ncs01 = VersionedSerializer.use(new MyNestedClassFormat01());

        @Override
        protected byte writeVersion() {
            return 0;
        }

        @Override
        protected MyClass.MyClassBuilder newBuilder() {
            return MyClass.builder();
        }

        @Override
        protected Collection<FormatVersion<MyClass, MyClass.MyClassBuilder>> getVersions() {
            return Arrays.asList(
                    newVersion(0).revision(0, this::write00, this::read00)
                                 .revision(1, this::write01, this::read01)
                                 .revision(2, this::write02, this::read02),
                    newVersion(1).revision(0, this::write10, this::read10));
        }

        //region Version 0 Revision 0 (Int Id, UTF name)

        private void write00(MyClass source, RevisionDataOutput output) throws IOException {
            output.writeInt((int) Math.min(Integer.MAX_VALUE, source.getId()));
            output.writeUTF(source.getName());
        }

        private void read00(RevisionDataInput input, MyClass.MyClassBuilder targetBuilder) throws IOException {
            targetBuilder.id(input.readInt()); // NOTE: this has been changed to Long in Revision 1
            targetBuilder.name(input.readUTF());
        }

        //endregion

        //region Version 0 Revision 1 (Long Id)

        private void write01(MyClass source, RevisionDataOutput output) throws IOException {
            output.writeLong(source.getId());
        }

        private void read01(RevisionDataInput input, MyClass.MyClassBuilder targetBuilder) throws IOException {
            targetBuilder.id(input.readLong());
        }

        //endregion

        //region Version 0 Revision 2 (+nestedClass, +tick, +isTrue)

        private void write02(MyClass target, RevisionDataOutput output) throws IOException {
            this.ncs01.serialize(output, target.nestedClass);
            output.writeInt(target.getTick());
            output.writeBoolean(target.isTrue());
            output.writeCollection(target.getStringList(), DataOutput::writeUTF);
            output.writeCollection(target.getNestedClasses(), this.ncs00::serialize);
        }

        private void read02(RevisionDataInput input, MyClass.MyClassBuilder targetBuilder) throws IOException {
            targetBuilder.nestedClass(this.ncs01.deserialize(input));
            targetBuilder.tick(input.readInt());
            targetBuilder.isTrue(input.readBoolean());
            targetBuilder.stringList(input.readCollection(RevisionDataInput::readUTF));
            targetBuilder.nestedClasses(input.readCollection(this.ncs00::deserialize));
        }

        //endregion

        //region Version 1 Revision 0 (Long Id, NestedClass, UTF Name)

        private void write10(MyClass target, RevisionDataOutput output) throws IOException {
            output.writeLong(target.getId());
            this.ncs01.serialize(output, target.nestedClass);
            output.writeUTF(target.getName());
            output.writeInt(target.getTick());
            output.writeBoolean(target.isTrue());
        }

        private void read10(RevisionDataInput input, MyClass.MyClassBuilder targetBuilder) throws IOException {
            targetBuilder.id(input.readLong());
            targetBuilder.nestedClass(this.ncs01.deserialize(input));
            targetBuilder.name(input.readUTF());
            targetBuilder.tick(input.readInt());
            targetBuilder.isTrue(input.readBoolean());
        }

        //endregion
    }

    private static class MyClassFormat1 extends MyClassFormat0 {
        @Override
        protected final byte writeVersion() {
            return 1;
        }
    }

    private static class MyNestedClassFormat00 extends FormatDescriptor.WithBuilder<MyNestedClass, MyNestedClass.MyNestedClassBuilder> {
        @Override
        protected byte writeVersion() {
            return 0;
        }

        protected byte writeRevision() {
            return 0;
        }

        @Override
        protected Collection<FormatVersion<MyNestedClass, MyNestedClass.MyNestedClassBuilder>> getVersions() {
            FormatVersion<MyNestedClass, MyNestedClass.MyNestedClassBuilder> v =
                    newVersion(0).revision(0, this::write00, this::read00);
            if (writeRevision() >= 1) {
                v.revision(1, this::write01, this::read01);
            }
            return Collections.singleton(v);
        }

        private void write00(MyNestedClass object, RevisionDataOutput stream) throws IOException {
            stream.writeUTF(object.name);
        }

        private void read00(DataInput s, MyNestedClass.MyNestedClassBuilder b) throws IOException {
            b.name(s.readUTF());
        }

        private void write01(MyNestedClass object, RevisionDataOutput stream) throws IOException {
            stream.writeUTF(object.name2);
        }

        private void read01(DataInput s, MyNestedClass.MyNestedClassBuilder b) throws IOException {
            b.name2(s.readUTF());
        }

        @Override
        protected MyNestedClass.MyNestedClassBuilder newBuilder() {
            return MyNestedClass.builder();
        }
    }

    private static class MyNestedClassFormat01 extends MyNestedClassFormat00 {
        protected byte writeRevision() {
            return 1;
        }
    }

    //endregion
}
