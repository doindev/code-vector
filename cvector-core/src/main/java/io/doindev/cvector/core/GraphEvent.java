package io.doindev.cvector.core;

import java.util.Map;

public sealed interface GraphEvent {

    record NodeUpsert(NodeKey key, Map<String, Object> props) implements GraphEvent {}

    record EdgeUpsert(NodeKey from, String type, NodeKey to, Map<String, Object> props) implements GraphEvent {}

    record NodeRemove(NodeKey key) implements GraphEvent {}
}
