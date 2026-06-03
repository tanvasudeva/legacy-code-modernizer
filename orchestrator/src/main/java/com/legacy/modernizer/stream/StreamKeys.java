package com.legacy.modernizer.stream;

public final class StreamKeys {

    public static final String REFACTORER    = "agent:refactorer";
    public static final String TEST_WRITER   = "agent:test_writer";
    public static final String DOC_GENERATOR = "agent:doc_generator";

    public static final String CONSUMER_GROUP = "legacy-modernizer";

    public static final String[] ALL = {REFACTORER, TEST_WRITER, DOC_GENERATOR};

    private StreamKeys() {}
}
