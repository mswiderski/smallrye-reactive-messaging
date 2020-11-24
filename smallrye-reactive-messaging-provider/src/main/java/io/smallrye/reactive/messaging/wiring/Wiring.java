package io.smallrye.reactive.messaging.wiring;

import java.util.*;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.AbstractMediator;
import io.smallrye.reactive.messaging.ChannelRegistry;
import io.smallrye.reactive.messaging.MediatorConfiguration;
import io.smallrye.reactive.messaging.annotations.Merge;
import io.smallrye.reactive.messaging.extension.*;

@ApplicationScoped
public class Wiring {

    public static final int DEFAULT_BUFFER_SIZE = 128;

    @Inject
    @ConfigProperty(name = "mp.messaging.emitter.default-buffer-size", defaultValue = "128")
    int defaultBufferSize;

    @Inject
    @ConfigProperty(name = "smallrye.messaging.emitter.default-buffer-size", defaultValue = "128")
    @Deprecated // Use mp.messaging.emitter.default-buffer-size instead
    int defaultBufferSizeLegacy;

    @Inject
    MediatorManager manager;

    private final List<Component> components;

    public Wiring() {
        components = new ArrayList<>();
    }

    public void prepare(ChannelRegistry registry, List<EmitterConfiguration> emitters, List<ChannelConfiguration> channels,
            List<MediatorConfiguration> mediators) {

        for (MediatorConfiguration mediator : mediators) {
            if (mediator.getOutgoing() != null && !mediator.getIncoming().isEmpty()) {
                components.add(new ProcessorMediatorComponent(manager, mediator));
            } else if (mediator.getOutgoing() != null) {
                components.add(new PublisherMediatorComponent(manager, mediator));
            } else {
                components.add(new SubscriberMediatorComponent(manager, mediator));
            }
        }

        for (ChannelConfiguration channel : channels) {
            components.add(new InjectedChannelComponent(channel));
        }

        for (EmitterConfiguration emitter : emitters) {
            components.add(new EmitterComponent(emitter, defaultBufferSize, defaultBufferSizeLegacy));
        }

        // At that point, the registry only contains connectors or managed channels
        for (Map.Entry<String, Boolean> entry : registry.getIncomingChannels().entrySet()) {
            components.add(new InboundConnectorComponent(entry.getKey(), entry.getValue()));
        }

        for (String outgoingName : registry.getOutgoingNames()) {
            components.add(new OutgoingConnectorComponent(outgoingName));
        }

    }

    public Graph resolve() {
        Set<Component> resolved = new LinkedHashSet<>();
        Set<ConsumingComponent> unresolved = new LinkedHashSet<>();

        // Initialize lists
        for (Component component : components) {
            if (component.isUpstreamResolved()) {
                resolved.add(component);
            } else {
                unresolved.add((ConsumingComponent) component);
            }
        }

        boolean doneOrStale = false;
        // Until everything is resolved or we got staled
        while (!doneOrStale) {
            List<ConsumingComponent> resolvedDuringThisTurn = new ArrayList<>();
            for (ConsumingComponent component : unresolved) {
                List<String> incomings = component.incomings();
                for (String incoming : incomings) {
                    List<Component> matches = getMatchesFor(incoming, resolved);
                    if (!matches.isEmpty()) {
                        matches.forEach(m -> bind(component, m));
                        if (component.isUpstreamResolved()) {
                            resolvedDuringThisTurn.add(component);
                        }
                    }
                }
            }

            resolved.addAll(resolvedDuringThisTurn);
            unresolved.removeAll(resolvedDuringThisTurn);

            doneOrStale = resolvedDuringThisTurn.isEmpty() || unresolved.isEmpty();

            // Update components consuming multiple incomings.
            for (Component component : resolved) {
                if (component instanceof ConsumingComponent) {
                    ConsumingComponent cc = (ConsumingComponent) component;
                    List<String> incomings = cc.incomings();
                    for (String incoming : incomings) {
                        List<Component> matches = getMatchesFor(incoming, resolved);
                        for (Component match : matches) {
                            bind(cc, match);
                        }
                    }
                }
            }
        }

        // Attempt to resolve from the unresolved set.
        List<ConsumingComponent> newlyResolved = new ArrayList<>();
        for (ConsumingComponent c : unresolved) {
            for (String incoming : c.incomings()) {
                // searched in unresolved
                List<Component> matches = getMatchesFor(incoming, unresolved);
                if (!matches.isEmpty()) {
                    newlyResolved.add(c);
                    matches.forEach(m -> bind(c, m));
                }
            }
        }
        if (!newlyResolved.isEmpty()) {
            unresolved.removeAll(newlyResolved);
            resolved.addAll(newlyResolved);
        }

        return new Graph(resolved, unresolved);

    }

    private void bind(ConsumingComponent consumer, Component provider) {
        consumer.connectUpstream(provider);
        provider.connectDownstream(consumer);
    }

    private List<Component> getMatchesFor(String incoming, Set<? extends Component> candidates) {
        List<Component> matches = new ArrayList<>();
        for (Component component : candidates) {
            Optional<String> outgoing = component.outgoing();
            if (outgoing.isPresent() && outgoing.get().equalsIgnoreCase(incoming)) {
                matches.add(component);
            }
        }
        return matches;
    }

    interface Component {

        void validate() throws WiringException;

        boolean isUpstreamResolved();

        boolean isDownstreamResolved();

        default Optional<String> outgoing() {
            return Optional.empty();
        }

        default List<String> incomings() {
            return Collections.emptyList();
        }

        default Set<Component> downstreams() {
            return Collections.emptySet();
        }

        default Set<Component> upstreams() {
            return Collections.emptySet();
        }

        default void connectDownstream(Component downstream) {
            throw new UnsupportedOperationException("Downstream connection not expected for " + this);
        }

        default void connectUpstream(Component upstream) {
            throw new UnsupportedOperationException("Upstream connection not expected for " + this);
        }

        void materialize(ChannelRegistry registry);
    }

    interface PublishingComponent extends Component {
        boolean broadcast();

        int getRequiredNumberOfSubscribers();

        default String getOutgoingChannel() {
            return outgoing().orElseThrow(() -> new IllegalStateException("Outgoing not configured for " + this));
        }

        @Override
        default boolean isDownstreamResolved() {
            return !downstreams().isEmpty();
        }
    }

    interface ConsumingComponent extends Component {

        @Override
        default boolean isUpstreamResolved() {
            return !upstreams().isEmpty();
        }

        boolean merge();

    }

    static class InboundConnectorComponent implements PublishingComponent {

        private final String name;
        private final boolean broadcast;
        private final Set<Component> downstreams = new LinkedHashSet<>();

        public InboundConnectorComponent(String name, boolean broadcast) {
            this.name = name;
            this.broadcast = broadcast;
        }

        @Override
        public Optional<String> outgoing() {
            return Optional.of(name);
        }

        @Override
        public boolean isUpstreamResolved() {
            return true;
        }

        @Override
        public Set<Component> downstreams() {
            return downstreams;
        }

        @Override
        public void connectDownstream(Component component) {
            downstreams.add(component);
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            // We are already registered and created.
        }

        @Override
        public Set<Component> upstreams() {
            return Collections.emptySet();
        }

        @Override
        public boolean broadcast() {
            return broadcast;
        }

        @Override
        public int getRequiredNumberOfSubscribers() {
            return 0;
        }

        @Override
        public String toString() {
            return "IncomingConnector{channel:'" + name + "', attribute:'mp.messaging.incoming." + name + "'}";
        }

        @Override
        public void validate() throws WiringException {
            if (!broadcast && downstreams().size() > 1) {
                throw new TooManyDownstreams(this);
            }
        }
    }

    static class OutgoingConnectorComponent implements ConsumingComponent {

        private final String name;
        private final Set<Component> upstreams = new LinkedHashSet<>();

        public OutgoingConnectorComponent(String name) {
            this.name = name;
        }

        @Override
        public boolean isDownstreamResolved() {
            // No downstream
            return true;
        }

        @Override
        public List<String> incomings() {
            return Collections.singletonList(name);
        }

        @Override
        public boolean merge() {
            return false; // TODO is that true?
        }

        @Override
        public void connectUpstream(Component upstream) {
            upstream.connectDownstream(this);
            upstreams.add(upstream);
        }

        @Override
        public Set<Component> upstreams() {
            return upstreams;
        }

        @Override
        public String toString() {
            return "OutgoingConnector{channel:'" + name + "', attribute:'mp.messaging.outgoing." + name + "'}";
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        @Override
        public void materialize(ChannelRegistry registry) {
            List<PublisherBuilder<? extends Message<?>>> publishers = registry.getPublishers(name);
            Multi<? extends Message<?>> merged = Multi.createBy().merging()
                    .streams(publishers.stream().map(PublisherBuilder::buildRs).collect(Collectors.toList()));
            // TODO Improve this.
            SubscriberBuilder<? extends Message<?>, Void> connector = registry.getSubscribers(name).get(0);
            Subscriber subscriber = connector.build();
            merged.subscribe().withSubscriber(subscriber);
        }

        @Override
        public void validate() throws WiringException {
            if (upstreams().size() > 1) {
                throw new TooManyUpstreams(this);
            }
        }
    }

    static class InjectedChannelComponent implements ConsumingComponent {

        private final String name;
        private final Set<Component> upstreams = new LinkedHashSet<>();

        public InjectedChannelComponent(ChannelConfiguration configuration) {
            this.name = configuration.channelName;
        }

        @Override
        public boolean isDownstreamResolved() {
            // No downstream.
            return true;
        }

        @Override
        public List<String> incomings() {
            return Collections.singletonList(name);
        }

        @Override
        public boolean merge() {
            return false; // TODO We may want to add this feature.
        }

        @Override
        public void connectUpstream(Component upstream) {
            upstreams.add(upstream);
        }

        @Override
        public Set<Component> upstreams() {
            return upstreams;
        }

        @Override
        public String toString() {
            return "@Channel{channel:'" + name + "'}";
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            // Nothing to be done for channel - look up happen during the subscription.
        }

        @Override
        public void validate() throws WiringException {
            if (upstreams().size() > 1) {
                throw new TooManyUpstreams(this);
            }
        }
    }

    static class EmitterComponent implements PublishingComponent {

        private final EmitterConfiguration configuration;
        private final Set<Component> downstreams = new LinkedHashSet<>();
        private final int defaultBufferSize;
        private final int defaultBufferSizeLegacy;

        public EmitterComponent(EmitterConfiguration configuration, int defaultBufferSize, int defaultBufferSizeLegacy) {
            this.configuration = configuration;
            this.defaultBufferSize = defaultBufferSize;
            this.defaultBufferSizeLegacy = defaultBufferSizeLegacy;
        }

        @Override
        public Optional<String> outgoing() {
            return Optional.of(configuration.name);
        }

        @Override
        public Set<Component> downstreams() {
            return downstreams;
        }

        @Override
        public boolean isUpstreamResolved() {
            return true;
        }

        @Override
        public void connectDownstream(Component component) {
            downstreams.add(component);
        }

        @Override
        public boolean broadcast() {
            return configuration.broadcast;
        }

        @Override
        public int getRequiredNumberOfSubscribers() {
            return configuration.numberOfSubscriberBeforeConnecting;
        }

        @Override
        public Set<Component> upstreams() {
            return Collections.emptySet();
        }

        @Override
        public String toString() {
            return "Emitter{channel:'" + getOutgoingChannel() + "'}";
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            Publisher<? extends Message<?>> publisher;
            int def = getDefaultBufferSize();
            if (configuration.isMutinyEmitter) {
                MutinyEmitterImpl<?> mutinyEmitter = new MutinyEmitterImpl<>(configuration, def);
                publisher = mutinyEmitter.getPublisher();
                registry.register(configuration.name, mutinyEmitter);
            } else {
                EmitterImpl<?> emitter = new EmitterImpl<>(configuration, def);
                publisher = emitter.getPublisher();
                registry.register(configuration.name, emitter);
            }
            registry.register(configuration.name, ReactiveStreams.fromPublisher(publisher));
        }

        private int getDefaultBufferSize() {
            if (defaultBufferSize == DEFAULT_BUFFER_SIZE && defaultBufferSizeLegacy != DEFAULT_BUFFER_SIZE) {
                return defaultBufferSizeLegacy;
            } else {
                return defaultBufferSize;
            }
        }

        @Override
        public void validate() throws WiringException {
            if (!configuration.broadcast && downstreams().size() > 1) {
                throw new TooManyDownstreams(this);
            }

            if (broadcast()
                    && getRequiredNumberOfSubscribers() != 0 && getRequiredNumberOfSubscribers() != downstreams.size()) {
                throw new UnsatisfiedBroadcast(this);
            }
        }
    }

    abstract static class MediatorComponent implements Component {
        final MediatorConfiguration configuration;
        final MediatorManager manager;

        protected MediatorComponent(MediatorManager manager, MediatorConfiguration configuration) {
            this.configuration = configuration;
            this.manager = manager;
        }
    }

    static class PublisherMediatorComponent extends MediatorComponent implements PublishingComponent {

        private final Set<Component> downstreams = new LinkedHashSet<>();

        protected PublisherMediatorComponent(MediatorManager manager, MediatorConfiguration configuration) {
            super(manager, configuration);
        }

        @Override
        public Optional<String> outgoing() {
            return Optional.of(configuration.getOutgoing());
        }

        @Override
        public boolean isUpstreamResolved() {
            return true;
        }

        @Override
        public Set<Component> downstreams() {
            return downstreams;
        }

        @Override
        public void connectDownstream(Component component) {
            downstreams.add(component);
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            AbstractMediator mediator = manager.createMediator(configuration);
            registry.register(configuration.getOutgoing(), mediator.getStream());
        }

        @Override
        public boolean broadcast() {
            return configuration.getBroadcast();
        }

        @Override
        public int getRequiredNumberOfSubscribers() {
            return configuration.getNumberOfSubscriberBeforeConnecting();
        }

        @Override
        public Set<Component> upstreams() {
            return Collections.emptySet();
        }

        @Override
        public String toString() {
            return "PublisherMethod{" +
                    "method:'" + configuration.methodAsString() + "', outgoing:'" + getOutgoingChannel() + "'}";
        }

        @Override
        public void validate() throws WiringException {
            if (!broadcast() && downstreams().size() > 1) {
                throw new TooManyDownstreams(this);
            }
            if (broadcast()
                    && getRequiredNumberOfSubscribers() != 0 && getRequiredNumberOfSubscribers() != downstreams.size()) {
                throw new UnsatisfiedBroadcast(this);
            }
        }
    }

    static class SubscriberMediatorComponent extends MediatorComponent implements ConsumingComponent {

        private final Set<Component> upstreams = new LinkedHashSet<>();

        protected SubscriberMediatorComponent(MediatorManager manager, MediatorConfiguration configuration) {
            super(manager, configuration);
        }

        @Override
        public Set<Component> upstreams() {
            return upstreams;
        }

        @Override
        public boolean isDownstreamResolved() {
            // No downstream
            return true;
        }

        @Override
        public List<String> incomings() {
            return configuration.getIncoming();
        }

        @Override
        public boolean merge() {
            return configuration.getMerge() != null;
        }

        @Override
        public void connectUpstream(Component upstream) {
            upstreams.add(upstream);
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            AbstractMediator mediator = manager.createMediator(configuration);

            boolean concat = configuration.getMerge() == Merge.Mode.CONCAT;
            boolean one = configuration.getMerge() == Merge.Mode.ONE;

            Multi<? extends Message<?>> aggregates;
            List<PublisherBuilder<? extends Message<?>>> publishers = new ArrayList<>();
            for (String channel : configuration.getIncoming()) {
                publishers.addAll(registry.getPublishers(channel));
            }
            if (concat) {
                aggregates = Multi.createBy().concatenating()
                        .streams(publishers.stream().map(PublisherBuilder::buildRs).collect(Collectors.toList()));
            } else if (one) {
                aggregates = Multi.createFrom().publisher(publishers.get(0).buildRs());
            } else {
                aggregates = Multi.createBy().merging()
                        .streams(publishers.stream().map(PublisherBuilder::buildRs).collect(Collectors.toList()));
            }

            mediator.connectToUpstream(ReactiveStreams.fromPublisher(aggregates));

            SubscriberBuilder<Message<?>, Void> subscriber = mediator.getComputedSubscriber();
            incomings().forEach(s -> registry.register(s, subscriber));

            mediator.run();
        }

        @Override
        public String toString() {
            return "SubscriberMethod{" +
                    "method:'" + configuration.methodAsString() + "', incoming:'" + String
                            .join(",", configuration.getIncoming())
                    + "'}";
        }

        private boolean hasAllUpstreams() {
            // A subscriber can have multiple incomings - all of them must be bound.
            for (String incoming : incomings()) {
                // For each incoming, check that we have a match
                if (upstreams().stream().noneMatch(c -> incoming.equals(c.outgoing().orElse(null)))) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public boolean isUpstreamResolved() {
            return hasAllUpstreams();
        }

        @Override
        public void validate() throws WiringException {
            // Check that for each incoming we have a single upstream or a merge strategy
            for (String incoming : incomings()) {
                List<Component> components = downstreams().stream()
                        .filter(c -> incoming.equals(c.outgoing().orElse(null)))
                        .collect(Collectors.toList());
                if (components.size() > 1 && !merge()) {
                    throw new TooManyUpstreams(this, incoming, components);
                }
            }

            if (!merge() && upstreams.size() != incomings().size()) {
                throw new TooManyUpstreams(this);
            }
        }
    }

    static class ProcessorMediatorComponent extends MediatorComponent
            implements ConsumingComponent, PublishingComponent {

        private final Set<Component> upstreams = new LinkedHashSet<>();
        private final Set<Component> downstreams = new LinkedHashSet<>();

        protected ProcessorMediatorComponent(MediatorManager manager, MediatorConfiguration configuration) {
            super(manager, configuration);
        }

        @Override
        public Set<Component> upstreams() {
            return upstreams;
        }

        @Override
        public List<String> incomings() {
            return configuration.getIncoming();
        }

        @Override
        public boolean merge() {
            return configuration.getMerge() != null;
        }

        @Override
        public void connectUpstream(Component upstream) {
            upstreams.add(upstream);
        }

        @Override
        public Optional<String> outgoing() {
            return Optional.of(configuration.getOutgoing());
        }

        @Override
        public Set<Component> downstreams() {
            return downstreams;
        }

        @Override
        public void connectDownstream(Component component) {
            downstreams.add(component);
        }

        @Override
        public boolean broadcast() {
            return configuration.getBroadcast();
        }

        @Override
        public int getRequiredNumberOfSubscribers() {
            return configuration.getNumberOfSubscriberBeforeConnecting();
        }

        private boolean hasAllUpstreams() {
            // A subscriber can have multiple incomings - all of them must be bound.
            for (String incoming : incomings()) {
                // For each incoming, check that we have a match
                if (upstreams().stream().noneMatch(c -> incoming.equals(c.outgoing().orElse(null)))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isUpstreamResolved() {
            return hasAllUpstreams();
        }

        @Override
        public String toString() {
            return "ProcessingMethod{" +
                    "method:'" + configuration.methodAsString()
                    + "', incoming:'" + String.join(",", configuration.getIncoming())
                    + "', outgoing:'" + getOutgoingChannel() + "'}";
        }

        @Override
        public void materialize(ChannelRegistry registry) {
            AbstractMediator mediator = manager.createMediator(configuration);

            boolean concat = configuration.getMerge() == Merge.Mode.CONCAT;
            boolean one = configuration.getMerge() == Merge.Mode.ONE;

            Multi<? extends Message<?>> aggregates;
            List<PublisherBuilder<? extends Message<?>>> publishers = new ArrayList<>();
            for (String channel : configuration.getIncoming()) {
                publishers.addAll(registry.getPublishers(channel));
            }
            if (concat) {
                aggregates = Multi.createBy().concatenating()
                        .streams(publishers.stream().map(PublisherBuilder::buildRs).collect(Collectors.toList()));
            } else if (one) {
                aggregates = Multi.createFrom().publisher(publishers.get(0).buildRs());
            } else {
                aggregates = Multi.createBy().merging()
                        .streams(publishers.stream().map(PublisherBuilder::buildRs).collect(Collectors.toList()));
            }

            mediator.connectToUpstream(ReactiveStreams.fromPublisher(aggregates));

            registry.register(getOutgoingChannel(), mediator.getStream());
        }

        @Override
        public void validate() throws WiringException {
            // Check that for each incoming we have a single upstream or a merge strategy
            for (String incoming : incomings()) {
                List<Component> components = downstreams().stream()
                        .filter(c -> incoming.equals(c.outgoing().orElse(null)))
                        .collect(Collectors.toList());
                if (components.size() > 1 && !merge()) {
                    throw new TooManyUpstreams(this, incoming, components);
                }
            }

            if (!merge() && upstreams.size() != incomings().size()) {
                throw new TooManyUpstreams(this);
            }

            if (!broadcast() && downstreams().size() > 1) {
                throw new TooManyDownstreams(this);
            }

            if (broadcast()
                    && getRequiredNumberOfSubscribers() != 0 && getRequiredNumberOfSubscribers() != downstreams.size()) {
                throw new UnsatisfiedBroadcast(this);
            }
        }
    }

}
