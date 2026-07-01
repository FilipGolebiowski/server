package gg.elcartel.data;

import gg.elcartel.data.config.Messages;
import gg.elcartel.data.config.MongoSettings;
import gg.elcartel.data.config.RedisSettings;
import gg.elcartel.data.redis.HandoffSignal;
import gg.elcartel.data.redis.LoginThrottle;
import gg.elcartel.data.redis.ProfileLock;
import gg.elcartel.data.redis.RateLimiter;
import gg.elcartel.data.redis.RedisMessenger;
import gg.elcartel.data.redis.SectorDest;
import gg.elcartel.data.redis.SessionRegistry;
import gg.elcartel.data.redis.ShardRegistry;
import gg.elcartel.data.redis.SectorDest;
import gg.elcartel.data.redis.TpDest;
import gg.elcartel.data.repo.AuthRepository;
import gg.elcartel.data.repo.EconomyRepository;
import gg.elcartel.data.repo.ModeProfileRepository;
import gg.elcartel.data.repo.PlayerAccountRepository;
import gg.elcartel.data.repo.PunishmentRepository;

/** Punkt wejscia warstwy danych: zrodlo + repozytoria (global + per-tryb) + prymitywy Redis. */
public final class CoreData implements AutoCloseable {

    private final CoreDataSource source;
    private final PlayerAccountRepository accounts;
    private final ModeProfileRepository modes;
    private final EconomyRepository economy;
    private final AuthRepository auth;
    private final PunishmentRepository punishments;
    private final ProfileLock locks;
    private final HandoffSignal handoff;
    private final SessionRegistry sessions;
    private final RateLimiter rateLimiter;
    private final LoginThrottle loginThrottle;
    private final ShardRegistry shards;
    private final SectorDest sectorDest;
    private final TpDest tpDest;
    private final RedisMessenger messenger;
    private final Messages messages;

    public CoreData(MongoSettings mongo, RedisSettings redis) {
        this.source = new CoreDataSource(mongo, redis);
        this.accounts = new PlayerAccountRepository(source.database());
        this.modes = new ModeProfileRepository(source.database());
        this.economy = new EconomyRepository(source.database());
        this.auth = new AuthRepository(source.database());
        this.punishments = new PunishmentRepository(source.database());
        this.locks = new ProfileLock(source.redis());
        this.handoff = new HandoffSignal(source.redis());
        this.sessions = new SessionRegistry(source.redis());
        this.rateLimiter = new RateLimiter(source.redis());
        this.loginThrottle = new LoginThrottle(source.redis());
        this.shards = new ShardRegistry(source.redis());
        this.sectorDest = new SectorDest(source.redis());
        this.tpDest = new TpDest(source.redis());
        this.messenger = new RedisMessenger(source.redisClient());
        this.messages = new Messages();
    }

    public PlayerAccountRepository accounts() { return accounts; }
    public ModeProfileRepository modes() { return modes; }
    public EconomyRepository economy() { return economy; }
    public AuthRepository auth() { return auth; }
    public PunishmentRepository punishments() { return punishments; }
    public ProfileLock locks() { return locks; }
    public HandoffSignal handoff() { return handoff; }
    public SessionRegistry sessions() { return sessions; }
    public RateLimiter rateLimiter() { return rateLimiter; }
    public LoginThrottle loginThrottle() { return loginThrottle; }
    public ShardRegistry shards() { return shards; }
    public SectorDest sectorDest() { return sectorDest; }
    public TpDest tpDest() { return tpDest; }
    public RedisMessenger messenger() { return messenger; }
    public Messages messages() { return messages; }
    public CoreDataSource source() { return source; }

    @Override
    public void close() {
        try { messenger.close(); } catch (Exception ignored) { }
        source.close();
    }
}
