package io.github.gonalez.znpcs.npc;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.github.gonalez.znpcs.ServersNPC;
import io.github.gonalez.znpcs.UnexpectedCallException;
import io.github.gonalez.znpcs.cache.CacheRegistry;
import io.github.gonalez.znpcs.npc.conversation.ConversationModel;
import io.github.gonalez.znpcs.npc.hologram.Hologram;
import io.github.gonalez.znpcs.npc.packet.PacketCache;
import io.github.gonalez.znpcs.user.ZUser;
import io.github.gonalez.znpcs.utility.Utils;
import io.github.gonalez.znpcs.utility.location.ZLocation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class NPC {
  private static final ConcurrentMap<Integer, NPC> NPC_MAP = new ConcurrentHashMap<>();
  
  private static final String PROFILE_TEXTURES = "textures";
  
  private static final String START_PREFIX = "[NPC] ";
  
  private final Set<ZUser> viewers = new HashSet<>();
  
  private final PacketCache packets = new PacketCache();
  
  private final NPCModel npcPojo;
  
  private final Hologram hologram;
  
  private final String npcName;

  private final NPCSkin npcSkin;
  
  private long lastMove = -1L;
  
  private int entityID;

  private Object glowColor;
  
  private Object tabConstructor, updateTabConstructor;
  
  private Object nmsEntity;
  
  private Object bukkitEntity;
  
  private UUID uuid;
  
  private GameProfile gameProfile;
  
  private NPCPath.PathInitializer npcPath;
  
  public NPC(NPCModel npcModel, boolean load) {
    this.npcPojo = npcModel;
    this.hologram = new Hologram(this);
    this.npcName = NamingType.DEFAULT.resolve(this);
    this.npcSkin = NPCSkin.forValues(npcModel.getSkin(), npcModel.getSignature());
    if (load)
      onLoad(); 
  }
  
  public NPC(NPCModel npcModel) {
    this(npcModel, false);
  }
  
  public void onLoad() {
    if (NPC_MAP.containsKey(getNpcPojo().getId()))
      throw new IllegalStateException("npc with id " + getNpcPojo().getId() + " already exists."); 
    this.gameProfile = new GameProfile(UUID.randomUUID(), "[NPC] " + this.npcName);
    this.gameProfile.getProperties().put("textures", new Property("textures", this.npcPojo.getSkin(), this.npcPojo.getSignature()));
    changeType(this.npcPojo.getNpcType());
    updateProfile(this.gameProfile.getProperties());
    setLocation(getNpcPojo().getLocation().bukkitLocation(), false);
    this.hologram.createHologram();
    if (this.npcPojo.getPathName() != null)
      setPath(NPCPath.AbstractTypeWriter.find(this.npcPojo.getPathName())); 
    this.npcPojo.getCustomizationMap().forEach((key, value) -> this.npcPojo.getNpcType().updateCustomization(this, key, value));
    NPC_MAP.put(getNpcPojo().getId(), this);
  }
  
  public NPCModel getNpcPojo() {
    return this.npcPojo;
  }
  
  public UUID getUUID() {
    return this.uuid;
  }
  
  public int getEntityID() {
    return this.entityID;
  }
  
  public Object getBukkitEntity() {
    return this.bukkitEntity;
  }
  
  public Object getNmsEntity() {
    return this.nmsEntity;
  }
  
  public Object getGlowColor() {
    return this.glowColor;
  }
  
  public GameProfile getGameProfile() {
    return this.gameProfile;
  }
  
  public NPCPath.PathInitializer getNpcPath() {
    return this.npcPath;
  }
  
  public Hologram getHologram() {
    return this.hologram;
  }
  
  public Set<ZUser> getViewers() {
    return this.viewers;
  }
  
  public PacketCache getPackets() {
    return this.packets;
  }
  
  public void setGlowColor(Object glowColor) {
    this.glowColor = glowColor;
  }

  public void setLocation(Location location, boolean updateTime) {
    try {
      if (this.npcPath == null) {
        lookAt(null, location, true);
        if (updateTime)
          this.lastMove = System.nanoTime(); 
        this.npcPojo.setLocation(new ZLocation(location = new Location(location.getWorld(), location.getBlockX() + 0.5D, location.getY(), location.getBlockZ() + 0.5D, location.getYaw(), location.getPitch())));
      }
      CacheRegistry.SET_LOCATION_METHOD.load().invoke(this.nmsEntity, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
      Object npcTeleportPacket = CacheRegistry.PACKET_PLAY_OUT_ENTITY_TELEPORT_CONSTRUCTOR.load().newInstance(this.nmsEntity);
      this.viewers.forEach(player -> Utils.sendPackets(player, npcTeleportPacket));
      this.hologram.setLocation(location, this.npcPojo.getNpcType().getHoloHeight());
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    } 
  }

  public void changeSkin(NPCSkin skinFetch) {
    this.npcPojo.setSkin(skinFetch.getTexture());
    this.npcPojo.setSignature(skinFetch.getSignature());
    this.gameProfile.getProperties().clear();
    this.gameProfile.getProperties().put("textures", new Property("textures",
        this.npcPojo.getSkin(), this.npcPojo.getSignature()));
    updateProfile(this.gameProfile.getProperties());
    deleteViewers();
  }

  public void setSecondLayerSkin() {
    try {
      Object dataWatcherObject = CacheRegistry.GET_DATA_WATCHER_METHOD.load().invoke(nmsEntity);
      if (Utils.versionNewer(9)) {
        CacheRegistry.SET_DATA_WATCHER_METHOD.load().invoke(dataWatcherObject,
            CacheRegistry.DATA_WATCHER_OBJECT_CONSTRUCTOR.load().newInstance(npcSkin.getLayerIndex(),
                CacheRegistry.DATA_WATCHER_REGISTER_FIELD.load()), (byte) 127);
      } else CacheRegistry.WATCH_DATA_WATCHER_METHOD.load().invoke(dataWatcherObject, 10, (byte) 127);
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    }
  }
  
  public synchronized void changeType(NPCType npcType) {
    deleteViewers();
    try {
      Object nmsWorld = CacheRegistry.GET_HANDLE_WORLD_METHOD.load().invoke(getLocation().getWorld());
      boolean isPlayer = (npcType == NPCType.PLAYER);
      this.nmsEntity = isPlayer ? this.packets.getProxyInstance().getPlayerPacket(nmsWorld, this.gameProfile) : (Utils.versionNewer(14) ? npcType.getConstructor().newInstance(npcType.getNmsEntityType(), nmsWorld) : npcType.getConstructor().newInstance(nmsWorld));
      this.bukkitEntity = CacheRegistry.GET_BUKKIT_ENTITY_METHOD.load().invoke(this.nmsEntity);
      this.uuid = (UUID) CacheRegistry.GET_UNIQUE_ID_METHOD.load().invoke(this.nmsEntity);
      if (isPlayer) {
        try {
          this.tabConstructor = CacheRegistry.PACKET_PLAY_OUT_PLAYER_INFO_CONSTRUCTOR.load().newInstance(CacheRegistry.ADD_PLAYER_FIELD.load(), Collections.singletonList(this.nmsEntity));
        } catch (Throwable e) {
          this.tabConstructor = CacheRegistry.PACKET_PLAY_OUT_PLAYER_INFO_CONSTRUCTOR.load().newInstance(CacheRegistry.ADD_PLAYER_FIELD.load(), nmsEntity);
          this.updateTabConstructor = CacheRegistry.PACKET_PLAY_OUT_PLAYER_INFO_CONSTRUCTOR.load().newInstance(CacheRegistry.UPDATE_LISTED_FIELD.load(), nmsEntity);
        }
        setSecondLayerSkin();
      }
      this.npcPojo.setNpcType(npcType);
      setLocation(getLocation(), false);
      this.packets.flushCache("spawnPacket", "removeTab");
      this.entityID = (Integer) CacheRegistry.GET_ENTITY_ID.load().invoke(this.nmsEntity);
      FunctionFactory.findFunctionsForNpc(this).forEach(function -> function.resolve(this));
      getPackets().getProxyInstance().update(this.packets);
      hologram.createHologram();
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    } 
  }
  
  public synchronized void spawn(ZUser user) {
    if (this.viewers.contains(user))
      throw new IllegalStateException(user.getUUID().toString() + " is already a viewer."); 
    try {
      this.viewers.add(user);
      boolean npcIsPlayer = (this.npcPojo.getNpcType() == NPCType.PLAYER);
      if (FunctionFactory.isTrue(this, "glow") || npcIsPlayer) {
        ImmutableList<Object> scoreboardPackets = this.packets.getProxyInstance().updateScoreboard(this);
        scoreboardPackets.forEach(p -> Utils.sendPackets(user, p));
      }
      if (npcIsPlayer) {
        if (FunctionFactory.isTrue(this, "mirror"))
          updateProfile(user.getGameProfile().getProperties());
        Utils.sendPackets(user, this.tabConstructor, updateTabConstructor);
      }
      Utils.sendPackets(user, this.packets.getProxyInstance().getSpawnPacket(this.nmsEntity, npcIsPlayer));
      if (FunctionFactory.isTrue(this, "holo"))
        this.hologram.spawn(user);
      updateMetadata(Collections.singleton(user));
      sendEquipPackets(user);
      lookAt(user, getLocation(), true);
      if (npcIsPlayer) {
        Object removeTabPacket = this.packets.getProxyInstance().getTabRemovePacket(this.nmsEntity);
        ServersNPC.SCHEDULER.scheduleSyncDelayedTask(() -> Utils.sendPackets(user,
            removeTabPacket, updateTabConstructor), 60);
      } 
    } catch (ReflectiveOperationException operationException) {
      delete(user);
      throw new UnexpectedCallException(operationException);
    } 
  }
  
  public synchronized void delete(ZUser user) {
    if (!this.viewers.contains(user))
      throw new IllegalStateException(user.getUUID().toString() + " is not a viewer.");
    this.viewers.remove(user);
    handleDelete(user);
  }
  
  private void handleDelete(ZUser user) {
    try {
      if (this.npcPojo.getNpcType() == NPCType.PLAYER)
        this.packets.getProxyInstance().getTabRemovePacket(this.nmsEntity);
      this.hologram.delete(user);
      Utils.sendPackets(user, this.packets.getProxyInstance().getDestroyPacket(this.entityID));
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    } 
  }
  
  public void lookAt(ZUser player, Location location, boolean rotation) {
    long lastMoveNanos = System.nanoTime() - this.lastMove;
    if (this.lastMove > 1L && lastMoveNanos < 1000000000L)
      return; 
    Location direction = rotation ? location : this.npcPojo.getLocation().bukkitLocation().clone().setDirection(location.clone().subtract(this.npcPojo.getLocation().bukkitLocation().clone()).toVector());
    try {
      Object lookPacket = CacheRegistry.PACKET_PLAY_OUT_ENTITY_LOOK_CONSTRUCTOR.load().newInstance(this.entityID, (byte) (int) (direction.getYaw() * 256.0F / 360.0F), (byte) (int) (direction.getPitch() * 256.0F / 360.0F), Boolean.TRUE);
      Object headRotationPacket = CacheRegistry.PACKET_PLAY_OUT_ENTITY_HEAD_ROTATION_CONSTRUCTOR.load().newInstance(this.nmsEntity, (byte) (int) (direction.getYaw() * 256.0F / 360.0F));
      if (player != null) {
        Utils.sendPackets(player, lookPacket, headRotationPacket);
      } else {
        this.viewers.forEach(players -> Utils.sendPackets(players, headRotationPacket));
      } 
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    } 
  }
  
  public void deleteViewers() {
    for (ZUser user : this.viewers)
      handleDelete(user); 
    this.viewers.clear();
  }
  
  protected void updateMetadata(Iterable<ZUser> users) {
    try {
      Object metaData = this.packets.getProxyInstance().getMetadataPacket(this.entityID, this.nmsEntity);
      for (ZUser user : users) {
        Utils.sendPackets(user, metaData);
      } 
    } catch (ReflectiveOperationException operationException) {
      operationException.getCause().printStackTrace();

      operationException.printStackTrace();
    } 
  }
  
  public void updateProfile(PropertyMap propertyMap) {
    if (this.npcPojo.getNpcType() != NPCType.PLAYER)
      return;
    try {
      Object gameProfileObj = CacheRegistry.GET_PROFILE_METHOD.load().invoke(this.nmsEntity);
      Utils.setValue(gameProfileObj, "name", this.gameProfile.getName());
      Utils.setValue(gameProfileObj, "id", this.gameProfile.getId());
      Utils.setValue(gameProfileObj, "properties", propertyMap);
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException);
    } 
  }
  
  public void sendEquipPackets(ZUser zUser) {
    if (this.npcPojo.getNpcEquip().isEmpty())
      return; 
    try {
      ImmutableList<Object> equipPackets = this.packets.getProxyInstance().getEquipPackets(this);
      equipPackets.forEach(o -> Utils.sendPackets(zUser, o));
    } catch (ReflectiveOperationException operationException) {
      throw new UnexpectedCallException(operationException.getCause());
    } 
  }

  public void setPath(NPCPath.AbstractTypeWriter typeWriter) {
    if (typeWriter == null) {
      this.npcPath = null;
      this.npcPojo.setPathName("none");
    } else {
      this.npcPath = typeWriter.getPath(this);
      this.npcPojo.setPathName(typeWriter.getName());
    } 
  }
  
  public void tryStartConversation(Player player) {
    ConversationModel conversation = this.npcPojo.getConversation();
    if (conversation == null)
      throw new IllegalStateException("can't find conversation"); 
    conversation.startConversation(this, player);
  }
  
  public Location getLocation() {
    return (this.npcPath != null) ? 
      this.npcPath.getLocation().bukkitLocation() : 
      this.npcPojo.getLocation().bukkitLocation();
  }
  
  public static NPC find(int id) {
    return NPC_MAP.get(id);
  }
  
  public static void unregister(int id) {
    NPC npc = find(id);
    if (npc == null)
      throw new IllegalStateException("can't find npc with id " + id);
    NPC_MAP.remove(id);
    npc.deleteViewers();
  }
  
  public static Collection<NPC> all() {
    return NPC_MAP.values();
  }
}
