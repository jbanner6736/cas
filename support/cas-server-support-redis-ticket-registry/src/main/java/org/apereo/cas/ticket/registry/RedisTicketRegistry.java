package org.apereo.cas.ticket.registry;

import org.apereo.cas.redis.core.CasRedisTemplate;
import org.apereo.cas.ticket.ServiceTicket;
import org.apereo.cas.ticket.Ticket;
import org.apereo.cas.ticket.TicketCatalog;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.UniqueTicketIdGenerator;
import org.apereo.cas.ticket.registry.pub.RedisTicketRegistryMessagePublisher;
import org.apereo.cas.ticket.serialization.TicketSerializationManager;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.crypto.CipherExecutor;
import org.apereo.cas.util.function.FunctionUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.redis.lettucemod.api.sync.RedisModulesCommands;
import com.redis.lettucemod.search.CreateOptions;
import com.redis.lettucemod.search.Document;
import com.redis.lettucemod.search.Field;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.hjson.JsonValue;
import org.hjson.Stringify;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.convert.KeyspaceConfiguration;
import org.springframework.data.redis.core.convert.MappingConfiguration;
import org.springframework.data.redis.core.index.IndexConfiguration;
import org.springframework.data.redis.core.mapping.RedisMappingContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Key-value ticket registry implementation that stores tickets in redis keyed on the ticket ID.
 *
 * @author serv
 * @since 5.1.0
 */
@Slf4j
public class RedisTicketRegistry extends AbstractTicketRegistry {

    private static final String SEARCH_INDEX_NAME = RedisTicketDocument.class.getSimpleName() + "Index";

    private final CasRedisTemplates casRedisTemplates;

    private final Cache<String, Ticket> ticketCache;

    private final RedisTicketRegistryMessagePublisher messagePublisher;

    private final Optional<RedisModulesCommands> redisModuleCommands;

    public RedisTicketRegistry(final CipherExecutor cipherExecutor,
                               final TicketSerializationManager ticketSerializationManager,
                               final TicketCatalog ticketCatalog,
                               final CasRedisTemplates casRedisTemplates,
                               final Cache<String, Ticket> ticketCache,
                               final RedisTicketRegistryMessagePublisher messagePublisher,
                               final Optional<RedisModulesCommands> redisModuleCommands) {
        super(cipherExecutor, ticketSerializationManager, ticketCatalog);

        this.casRedisTemplates = casRedisTemplates;
        this.ticketCache = ticketCache;
        this.messagePublisher = messagePublisher;
        this.redisModuleCommands = redisModuleCommands;

        createIndexesIfNecessary();
    }

    @Override
    public long deleteAll() {
        val size = new AtomicLong();
        var options = ScanOptions.scanOptions().match(RedisCompositeKey.forTickets().toKeyPattern()).build();
        try (val result = casRedisTemplates.getTicketsRedisTemplate().scan(options)) {
            casRedisTemplates.getTicketsRedisTemplate().executePipelined((RedisCallback<Object>) connection -> {
                StreamSupport.stream(result.spliterator(), false).forEach(id -> {
                    connection.keyCommands().del(id.getBytes(StandardCharsets.UTF_8));
                    size.getAndIncrement();
                });
                return null;
            });
        }

        options = ScanOptions.scanOptions().match(RedisCompositeKey.forPrincipal().toKeyPattern()).build();
        try (val result = casRedisTemplates.getSessionsRedisTemplate().scan(options)) {
            casRedisTemplates.getSessionsRedisTemplate().executePipelined((RedisCallback<Object>) connection -> {
                StreamSupport.stream(result.spliterator(), false)
                    .forEach(id -> connection.keyCommands().del(id.getBytes(StandardCharsets.UTF_8)));
                return null;
            });
        }
        ticketCache.invalidateAll();
        messagePublisher.deleteAll();
        return size.get();
    }

    @Override
    public long deleteSingleTicket(final Ticket ticket) {
        val redisTicketsKey = RedisCompositeKey.forTickets().withTicketId(ticket.getPrefix(), digestIdentifier(ticket.getId()));
        val redisKeyPattern = redisTicketsKey.toKeyPattern();
        val count = Stream.of(redisKeyPattern)
            .mapToInt(id -> BooleanUtils.toBoolean(casRedisTemplates.getTicketsRedisTemplate().delete(id)) ? 1 : 0)
            .sum();

        val principal = digestIdentifier(getPrincipalIdFrom(ticket));
        val redisPrincipalKey = RedisCompositeKey.forPrincipal().withQuery(principal);
        val redisPrincipalPattern = redisPrincipalKey.toKeyPattern();
        Stream.of(redisPrincipalPattern)
            .forEach(id -> casRedisTemplates.getSessionsRedisTemplate().delete(id));

        ticketCache.invalidate(redisTicketsKey.getQuery());
        messagePublisher.delete(ticket);
        return count;
    }

    @Override
    public void addTicket(final Stream<? extends Ticket> toSave) {
        FunctionUtils.doAndHandle(__ ->
            casRedisTemplates.getTicketsRedisTemplate().executePipelined((RedisCallback<Object>) connection -> {
                toSave.forEach(this::addTicketInternal);
                return null;
            }));
    }

    @Override
    public void addTicketInternal(final Ticket ticket) {
        FunctionUtils.doAndHandle(__ -> {
            LOGGER.debug("Adding ticket [{}]", ticket);
            addOrUpdateTicket(ticket);
            messagePublisher.add(ticket);
        });
    }

    @Override
    public Ticket updateTicket(final Ticket ticket) {
        return FunctionUtils.doAndHandle(() -> {
            LOGGER.debug("Updating ticket [{}]", ticket);
            addOrUpdateTicket(ticket);
            messagePublisher.update(ticket);
            return ticket;
        });
    }

    @Override
    public Ticket getTicket(final String ticketId, final Predicate<Ticket> predicate) {
        return FunctionUtils.doAndHandle(() -> {
            val ticketPrefix = StringUtils.substring(ticketId, 0, ticketId.indexOf(UniqueTicketIdGenerator.SEPARATOR));
            val redisKey = RedisCompositeKey.forTickets().withTicketId(ticketPrefix, digestIdentifier(ticketId));
            return getTicketFromRedisByKey(predicate, redisKey);
        });

    }

    @Override
    public Collection<? extends Ticket> getTickets() {
        try (val ticketsStream = stream()) {
            return ticketsStream.collect(Collectors.toSet());
        }
    }

    @Override
    public Stream<? extends Ticket> stream() {
        return fetchKeysForTickets()
            .map(redisKey -> {
                val adapter = buildRedisKeyValueAdapter(redisKey);
                val document = adapter.get(redisKey, redisKey, RedisTicketDocument.class);
                if (document == null) {
                    casRedisTemplates.getTicketsRedisTemplate().delete(redisKey);
                    return null;
                }
                return document;
            })
            .filter(Objects::nonNull)
            .map(RedisTicketDocument.class::cast)
            .map(this::deserializeAsTicket)
            .map(this::decodeTicket)
            .filter(Objects::nonNull)
            .peek(ticket -> {
                if (!ticket.isExpired()) {
                    val redisTicketsKey = RedisCompositeKey.forTickets()
                        .withTicketId(ticket.getPrefix(), digestIdentifier(ticket.getId()));
                    ticketCache.put(redisTicketsKey.getQuery(), ticket);
                }
            });
    }


    @Override
    public Stream<? extends Ticket> getSessionsFor(final String principalId) {
        val userId = digestIdentifier(principalId);
        val redisPrincipalKey = RedisCompositeKey.forPrincipal().withQuery(userId);
        val members = casRedisTemplates.getSessionsRedisTemplate()
            .boundSetOps(redisPrincipalKey.toKeyPattern()).members();
        return Objects.requireNonNull(members)
            .stream()
            .filter(Objects::nonNull)
            .map(ticketId -> {
                val redisKey = RedisCompositeKey.forTickets().withTicketId(TicketGrantingTicket.PREFIX, ticketId);
                return getTicketFromRedisByKey(ticket -> !ticket.isExpired(), redisKey);
            })
            .map(this::decodeTicket)
            .filter(Objects::nonNull)
            .filter(ticket -> !ticket.isExpired());
    }

    @Override
    public long sessionCount() {
        val options = ScanOptions.scanOptions()
            .match(RedisCompositeKey.forTickets().withIdPattern(TicketGrantingTicket.PREFIX).toKeyPattern()).build();
        try (val result = casRedisTemplates.getTicketsRedisTemplate().scan(options)) {
            return result.stream().count();
        }
    }

    @Override
    public long serviceTicketCount() {
        val options = ScanOptions.scanOptions()
            .match(RedisCompositeKey.forTickets().withIdPattern(ServiceTicket.PREFIX).toKeyPattern()).build();
        try (val result = casRedisTemplates.getTicketsRedisTemplate().scan(options)) {
            return result.stream().count();
        }
    }

    @Override
    public Stream<? extends Ticket> getSessionsWithAttributes(final Map<String, List<Object>> queryAttributes) {
        return redisModuleCommands
            .map(command -> {
                val criteria = new ArrayList<String>();
                queryAttributes.forEach((key, value) -> value.forEach(queryValue -> {
                    val escapedValue = isCipherExecutorEnabled()
                        ? digestIdentifier(queryValue.toString())
                        : StringUtils.replace(queryValue.toString(), "-", "\\-");
                    criteria.add(String.format("(%s" + (isCipherExecutorEnabled() ? " " : "_") + "*%s)", digestIdentifier(key), escapedValue));
                }));
                val query = String.format("(%s) @%s:%s", String.join("|", criteria),
                    RedisTicketDocument.FIELD_NAME_PREFIX, TicketGrantingTicket.PREFIX);
                LOGGER.debug("Executing search query [{}]", query);
                val results = command.ftSearch(SEARCH_INDEX_NAME, query);
                return results
                    .stream()
                    .map(document -> {
                        val searchDoc = (Document) document;
                        val redisDoc = RedisTicketDocument.from(searchDoc);
                        val ticket = deserializeAsTicket(redisDoc);
                        return decodeTicket(ticket);
                    })
                    .filter(ticket -> !((Ticket) ticket).isExpired());
            })
            .orElseGet(() -> super.getSessionsWithAttributes(queryAttributes));
    }

    private Stream<String> fetchKeysForTickets() {
        return fetchKeysForTickets(RedisCompositeKey.forTickets().toKeyPattern());
    }

    private Stream<String> fetchKeysForTickets(final String key) {
        LOGGER.debug("Loading keys for pattern [{}]", key);
        return Objects.requireNonNull(casRedisTemplates.getTicketsRedisTemplate().keys(key)).stream();
    }

    protected RedisTicketDocument buildTicketAsDocument(final Ticket ticket) {
        return FunctionUtils.doUnchecked(() -> {
            val encTicket = encodeTicket(ticket);
            val json = serializeTicket(encTicket);
            FunctionUtils.throwIf(StringUtils.isBlank(json),
                () -> new IllegalArgumentException("Ticket " + ticket.getId() + " cannot be serialized to JSON"));
            LOGGER.trace("Serialized ticket into a JSON document as\n [{}]",
                JsonValue.readJSON(json).toString(Stringify.FORMATTED));

            val principal = getPrincipalIdFrom(ticket);
            val attributeMap = (Map<String, Object>) collectAndDigestTicketAttributes(ticket);
            val attributesEncoded = attributeMap
                .entrySet()
                .stream()
                .map(entry -> {
                    val entryValues = (List) entry.getValue();
                    val valueList = entryValues.stream().map(Object::toString).collect(Collectors.joining(","));
                    return entry.getKey() + (isCipherExecutorEnabled() ? " " : "_") + valueList;
                })
                .collect(Collectors.joining(","));

            return RedisTicketDocument.builder()
                .type(encTicket.getClass().getName())
                .ticketId(encTicket.getId())
                .json(json)
                .prefix(ticket.getPrefix())
                .principal(digestIdentifier(principal))
                .attributes(attributesEncoded)
                .build();
        });
    }

    private Ticket getTicketFromRedisByKey(final Predicate<Ticket> predicate, final RedisCompositeKey redisKey) {
        val ticket = Optional.ofNullable(ticketCache.getIfPresent(redisKey.getQuery()))
            .map(this::decodeTicket)
            .filter(predicate)
            .stream()
            .findFirst()
            .orElseGet(() -> {
                val redisKeyPattern = redisKey.toKeyPattern();
                return Stream.of(redisKeyPattern)
                    .map(key -> {
                        val adapter = buildRedisKeyValueAdapter(key);
                        return adapter.get(key, key, RedisTicketDocument.class);
                    })
                    .filter(Objects::nonNull)
                    .map(this::deserializeAsTicket)
                    .map(this::decodeTicket)
                    .filter(predicate)
                    .findFirst()
                    .orElse(null);
            });
        if (ticket != null && predicate.test(ticket) && !ticket.isExpired()) {
            ticketCache.put(redisKey.getQuery(), ticket);
            return ticket;
        }
        ticketCache.invalidate(redisKey.getQuery());
        messagePublisher.delete(ticket);
        return null;
    }


    private RedisCompositeKey addOrUpdateTicket(final Ticket ticket) {
        val userId = digestIdentifier(getPrincipalIdFrom(ticket));

        val digestedId = digestIdentifier(ticket.getId());
        val redisKey = RedisCompositeKey.forTickets().withTicketId(ticket.getPrefix(), digestedId);

        val timeout = RedisCompositeKey.getTimeout(ticket);
        val redisKeyPattern = redisKey.toKeyPattern();

        val ticketDocument = buildTicketAsDocument(ticket);

        casRedisTemplates.getTicketsRedisTemplate().boundValueOps(redisKeyPattern).set(ticketDocument, timeout, TimeUnit.SECONDS);
        val adapter = buildRedisKeyValueAdapter(redisKeyPattern);
        adapter.put(ticketDocument.getTicketId(), ticketDocument, redisKeyPattern);
        casRedisTemplates.getTicketsRedisTemplate().expire(redisKeyPattern, timeout, TimeUnit.SECONDS);
        ticketCache.put(redisKey.getQuery(), ticket);

        if (StringUtils.isNotBlank(userId) && ticket instanceof TicketGrantingTicket) {
            val redisPrincipalPattern = RedisCompositeKey.forPrincipal().withQuery(userId).toKeyPattern();
            val ops = casRedisTemplates.getSessionsRedisTemplate().boundSetOps(redisPrincipalPattern);
            ops.add(digestedId);
            ops.expire(timeout, TimeUnit.SECONDS);
        }

        return redisKey;
    }

    private RedisKeyValueAdapter buildRedisKeyValueAdapter(final String redisKeyPattern) {
        val redisMappingContext = new RedisMappingContext(
            new MappingConfiguration(new IndexConfiguration(), new KeyspaceConfiguration() {
                @Override
                protected Iterable<KeyspaceSettings> initialConfiguration() {
                    return Collections.singleton(new KeyspaceSettings(RedisTicketDocument.class, redisKeyPattern));
                }
            }));

        val adapter = new RedisKeyValueAdapter(casRedisTemplates.getTicketsRedisTemplate(), redisMappingContext) {
            @Override
            public byte[] createKey(final String keyspace, final String id) {
                return toBytes(redisKeyPattern);
            }
        };
        adapter.afterPropertiesSet();
        return adapter;
    }

    private void createIndexesIfNecessary() {
        redisModuleCommands.ifPresent(command -> {
            val options = CreateOptions.<String, RedisTicketDocument>builder()
                .prefix(RedisCompositeKey.CAS_TICKET_PREFIX + ':')
                .maxTextFields(true)
                .build();
            val createIndex = command.ftList().stream().noneMatch(idx -> SEARCH_INDEX_NAME.equalsIgnoreCase(idx.toString()));
            if (createIndex) {
                val indexFields = CollectionUtils.wrapList(
                    Field.text(RedisTicketDocument.FIELD_NAME_ID).build(),
                    Field.text(RedisTicketDocument.FIELD_NAME_ATTRIBUTES).build(),
                    Field.text(RedisTicketDocument.FIELD_NAME_PRINCIPAL).build(),
                    Field.text(RedisTicketDocument.FIELD_NAME_TYPE).build(),
                    Field.text(RedisTicketDocument.FIELD_NAME_PREFIX).build());
                command.ftCreate(SEARCH_INDEX_NAME, options, indexFields.toArray(new Field[]{}));
            }
        });
    }

    protected Ticket deserializeAsTicket(final RedisTicketDocument document) {
        return ticketSerializationManager.deserializeTicket(document.getJson(), document.getType());
    }

    @Data
    public static class CasRedisTemplates {
        private final CasRedisTemplate<String, RedisTicketDocument> ticketsRedisTemplate;
        
        private final CasRedisTemplate<String, String> sessionsRedisTemplate;
    }
}
