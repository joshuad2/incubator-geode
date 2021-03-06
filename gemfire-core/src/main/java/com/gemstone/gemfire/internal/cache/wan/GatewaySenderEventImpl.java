/*
 * ========================================================================= 
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved. 
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 * =========================================================================
 */

package com.gemstone.gemfire.internal.cache.wan;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.SerializedCacheValue;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEvent;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.cache.wan.EventSequenceID;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.Version;
import com.gemstone.gemfire.internal.VersionedDataInputStream;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EnumListenerEvent;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.WrappedCallbackArgument;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerHelper;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Class <code>GatewaySenderEventImpl</code> represents an event sent between
 * <code>GatewaySender</code>
 * 
 * @author Suranjan Kumar
 * 
 * @since 7.0
 * 
 */
public class GatewaySenderEventImpl implements 
    AsyncEvent, DataSerializableFixedID, Conflatable, Sizeable {
  private static final long serialVersionUID = -5690172020872255422L;

  protected static final Object TOKEN_UN_INITIALIZED = new Object();
  
  protected static final Object TOKEN_NULL = new Object();

  protected static final short VERSION = 0x11;
  
  protected EnumListenerEvent operation;

  protected EntryEventImpl entryEvent;
  
  protected Object substituteValue;

  /**
   * The action to be taken (e.g. AFTER_CREATE)
   */
  protected int action;
  
  /**
   * The operation detail of EntryEvent (e.g. LOAD, PUTALL etc.)
   */
  protected int operationDetail;
  
  /**
   * The number of parts for the <code>Message</code>
   * 
   * @see com.gemstone.gemfire.internal.cache.tier.sockets.Message
   */
  protected int numberOfParts;

  /**
   * The identifier of this event
   */
  protected EventID id;

  /**
   * The <code>Region</code> that was updated
   */
  private LocalRegion region;

  /**
   * The name of the region being affected by this event
   */
  protected String regionPath;

  /**
   * The key being affected by this event
   */
  protected Object key = TOKEN_UN_INITIALIZED;

  /**
   * The serialized new value for this event's key
   */
  protected byte[] value;

  /**
   * Whether the value is a serialized object or just a byte[]
   */
  protected byte valueIsObject;

  /**
   * The callback argument for this event
   */
  protected GatewaySenderEventCallbackArgument callbackArgument;

  /**
   * The version timestamp
   */
  protected long versionTimeStamp;
  
  /**
   * Whether this event is a possible duplicate
   */
  protected boolean possibleDuplicate;

  /**
   * Whether this event is acknowledged after the ack received by
   * AckReaderThread. As of now this is getting used for PDX related
   * GatewaySenderEvent. But can be extended for for other GatewaySenderEvent.
   */
  protected volatile boolean isAcked;
  
  /**
   * Whether this event is dispatched by dispatcher. As of now this is getting
   * used for PDX related GatewaySenderEvent. But can be extended for for other
   * GatewaySenderEvent.
   */
  protected volatile boolean isDispatched;
  /**
   * The creation timestamp in ms
   */
  protected long creationTime;

  /**
   * For ParalledGatewaySender we need bucketId of the PartitionRegion on which
   * the update operation was applied.
   */
  protected int bucketId;

  protected Long shadowKey = new Long(-1);

  /**
   * Is this thread in the process of serializing this event?
   */
  public static final ThreadLocal isSerializingValue = new ThreadLocal() {
    @Override
    protected Object initialValue() {
      return Boolean.FALSE;
    }
  };

  private static final int CREATE_ACTION = 0;

  private static final int UPDATE_ACTION = 1;

  private static final int DESTROY_ACTION = 2;

  private static final int VERSION_ACTION = 3;
  
  /**
   * Static constants for Operation detail of EntryEvent.
   */
  private static final int OP_DETAIL_NONE = 10;
  
  private static final int OP_DETAIL_LOCAL_LOAD = 11;
  
  private static final int OP_DETAIL_NET_LOAD = 12;
  
  private static final int OP_DETAIL_PUTALL = 13;
  
  private static final int OP_DETAIL_REMOVEALL = 14;

  /**
   * Is this thread in the process of deserializing this event?
   */
  public static final ThreadLocal isDeserializingValue = new ThreadLocal() {
    @Override
    protected Object initialValue() {
      return Boolean.FALSE;
    }
  };

  /**
   * Constructor. No-arg constructor for data serialization.
   * 
   * @see DataSerializer
   */
  public GatewaySenderEventImpl() {
  }

  /**
   * Constructor. Creates an initialized <code>GatewayEventImpl</code>
   * 
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>CacheEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   * @param substituteValue
   *          The value to be enqueued instead of the value in the event.
   * 
   * @throws IOException
   */
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent event,
      Object substituteValue) throws IOException {
    this(operation, event, substituteValue, true);
  }

  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent event,
      Object substituteValue, boolean initialize, int bucketId)
      throws IOException {
    this(operation, event, substituteValue, initialize);
    this.bucketId = bucketId;
  }

  /**
   * Constructor.
   * 
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>CacheEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   * @param substituteValue
   *          The value to be enqueued instead of the value in the event.
   * @param initialize
   *          Whether to initialize this instance
   * 
   * @throws IOException
   */
  public GatewaySenderEventImpl(EnumListenerEvent operation, CacheEvent event,
      Object substituteValue, boolean initialize) throws IOException {
    // Set the operation and event
    this.operation = operation;
    this.entryEvent = (EntryEventImpl)event;
    this.substituteValue = substituteValue;

    // Initialize the region name. This is being done here because the event
    // can get serialized/deserialized (for some reason) between the time
    // it is set above and used (in initialize). If this happens, the
    // region is null because it is a transient field of the event.
    this.region = (LocalRegion)this.entryEvent.getRegion();
    this.regionPath = this.region.getFullPath();

    // Initialize the unique id
    initializeId();

    // Initialize possible duplicate
    this.possibleDuplicate = this.entryEvent.isPossibleDuplicate(); 
    
    //Initialize ack and dispatch status of events
    this.isAcked = false;
    this.isDispatched = false;
    

    // Initialize the creation timestamp
    this.creationTime = System.currentTimeMillis();

    if (this.entryEvent.getVersionTag() != null) {
      this.versionTimeStamp = this.entryEvent.getVersionTag().getVersionTimeStamp();
    }
    // Initialize the remainder of the event if necessary
    if (initialize) {
      initialize();
    }
  }

  /**
   * Returns this event's action
   * 
   * @return this event's action
   */
  public int getAction() {
    return this.action;
  }

  /**
   * Returns this event's operation
   * 
   * @return this event's operation
   */
  public Operation getOperation() {
    Operation op = null;
    switch (this.action) {
    case CREATE_ACTION:
      switch (this.operationDetail) {
      case OP_DETAIL_LOCAL_LOAD:
        op = Operation.LOCAL_LOAD_CREATE;
        break;
      case OP_DETAIL_NET_LOAD:
    	op = Operation.NET_LOAD_CREATE;
    	break;
      case OP_DETAIL_PUTALL:
        op = Operation.PUTALL_CREATE;
    	break;
      case OP_DETAIL_NONE:
    	op = Operation.CREATE;
    	break;
      //if operationDetail is none of the above, then default should be NONE 
      default:
    	op = Operation.CREATE;
    	break;
      }
      break;
    case UPDATE_ACTION:
      switch (this.operationDetail) {
      case OP_DETAIL_LOCAL_LOAD:
    	op = Operation.LOCAL_LOAD_UPDATE;
    	break;
      case OP_DETAIL_NET_LOAD:
    	op = Operation.NET_LOAD_UPDATE;
    	break;
      case OP_DETAIL_PUTALL:
    	op = Operation.PUTALL_UPDATE;
    	break;
      case OP_DETAIL_NONE:
    	op = Operation.UPDATE;
    	break;
      //if operationDetail is none of the above, then default should be NONE 
      default:
    	op = Operation.UPDATE;
    	break;
      }
      break;
    case DESTROY_ACTION:
      if (this.operationDetail == OP_DETAIL_REMOVEALL) {
        op = Operation.REMOVEALL_DESTROY;
      } else {
        op = Operation.DESTROY;
      }
      break;
    case VERSION_ACTION:
      op = Operation.UPDATE_VERSION_STAMP;
    }
    return op;
  }

  public EntryEvent getEntryEvent(){
    return this.entryEvent;
  }
  
  public Object getSubstituteValue() {
    return this.substituteValue;
  }
  
  public EnumListenerEvent getEnumListenerEvent(){
    return this.operation;
  }
  /**
   * Return this event's region name
   * 
   * @return this event's region name
   */
  public String getRegionPath() {
    return this.regionPath;
  }

  /**
   * Returns this event's key
   * 
   * @return this event's key
   */
  public Object getKey() {
    // TODO:Asif : Ideally would like to have throw exception if the key
    // is TOKEN_UN_INITIALIZED, but for the time being trying to retain the GFE
    // behaviour
    // of returning null if getKey is invoked on un-initialized gateway event
    return this.key == TOKEN_UN_INITIALIZED ? null : this.key;
  }

  /**
   * Returns this event's serialized value
   * 
   * @return this event's serialized value
   */
  public byte[] getValue() {
    return this.value;
  }

  /**
   * Returns whether this event's value is a serialized object
   * 
   * @return whether this event's value is a serialized object
   */
  public byte getValueIsObject() {
    return this.valueIsObject;
  }

  /**
   * Return this event's callback argument
   * 
   * @return this event's callback argument
   */
  public Object getCallbackArgument() {
    Object result = getSenderCallbackArgument();
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument)result;
      result = wca.getOriginalCallbackArg();
    }
    return result;
  }

  public GatewaySenderEventCallbackArgument getSenderCallbackArgument() {
    return this.callbackArgument;
  }

  /**
   * Return this event's number of parts
   * 
   * @return this event's number of parts
   */
  public int getNumberOfParts() {
    return this.numberOfParts;
  }

  /**
   * Return this event's deserialized value
   * 
   * @return this event's deserialized value
   */
  public Object getDeserializedValue() {
    isDeserializingValue.set(Boolean.TRUE);
    Object obj = deserialize(this.value);
    isDeserializingValue.set(Boolean.FALSE);
    return obj;
  }

  public byte[] getSerializedValue() {
    return this.value;
  }

  public void setPossibleDuplicate(boolean possibleDuplicate) {
    this.possibleDuplicate = possibleDuplicate;
  }

  public boolean getPossibleDuplicate() {
    return this.possibleDuplicate;
  }

  public long getCreationTime() {
    return this.creationTime;
  }

  public int getDSFID() {
    return GATEWAY_SENDER_EVENT_IMPL;
  }

  public void toData(DataOutput out) throws IOException {
    // Make sure we are initialized before we serialize.
    if (this.key == TOKEN_UN_INITIALIZED) {
      initialize();
    }
    out.writeShort(VERSION);
    out.writeInt(this.action);
    out.writeInt(this.numberOfParts);
    // out.writeUTF(this._id);
    DataSerializer.writeObject(this.id, out);
    DataSerializer.writeString(this.regionPath, out);
    out.writeByte(this.valueIsObject);
    DataSerializer.writeObject(this.key, out);
    DataSerializer.writeByteArray(this.value, out);
    DataSerializer.writeObject(this.callbackArgument, out);
    out.writeBoolean(this.possibleDuplicate);
    out.writeLong(this.creationTime);
    out.writeInt(this.bucketId);
    out.writeLong(this.shadowKey);
    out.writeLong(getVersionTimeStamp());
  }

  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    short version = in.readShort();
    if (version != VERSION) {
      // warning?
    }
    this.action = in.readInt();
    this.numberOfParts = in.readInt();
    // this._id = in.readUTF();
    if (version < 0x11 &&
        (in instanceof InputStream) &&
        InternalDataSerializer.getVersionForDataStream(in) == Version.CURRENT) {
      in = new VersionedDataInputStream((InputStream)in, Version.GFE_701);
    }
    this.id = (EventID)DataSerializer.readObject(in);   
    // TODO:Asif ; Check if this violates Barry's logic of not assiging VM
    // specific Token.FROM_GATEWAY
    // and retain the serialized Token.FROM_GATEWAY
    // this._id.setFromGateway(false);
    this.regionPath = DataSerializer.readString(in);
    this.valueIsObject = in.readByte();
    this.key = DataSerializer.readObject(in);
    this.value = DataSerializer.readByteArray(in);
    this.callbackArgument = (GatewaySenderEventCallbackArgument)DataSerializer
        .readObject(in);
    this.possibleDuplicate = in.readBoolean();
    this.creationTime = in.readLong();
    this.bucketId = in.readInt();
    this.shadowKey = in.readLong();
    this.versionTimeStamp = in.readLong();
  }

  @Override
  public String toString() {
    StringBuffer buffer = new StringBuffer();
    buffer.append("SenderEventImpl[").append("id=").append(this.id)
        .append(";action=").append(this.action).append(";operation=")
        .append(getOperation()).append(";region=").append(this.regionPath)
        .append(";key=").append(this.key).append(";value=")
        .append(deserialize(this.value)).append(";valueIsObject=")
        .append(this.valueIsObject).append(";numberOfParts=")
        .append(this.numberOfParts).append(";callbackArgument=")
        .append(this.callbackArgument).append(";possibleDuplicate=")
        .append(this.possibleDuplicate).append(";creationTime=")
        .append(this.creationTime).append(";shadowKey= ")
        .append(this.shadowKey)
        .append(";timeStamp=").append(this.versionTimeStamp)
        .append(";acked=").append(this.isAcked)
        .append(";dispatched=").append(this.isDispatched)
        .append("]");
    return buffer.toString();
  }

  public static boolean isSerializingValue() {
    return ((Boolean)isSerializingValue.get()).booleanValue();
  }

  public static boolean isDeserializingValue() {
    return ((Boolean)isDeserializingValue.get()).booleanValue();
  }

  public Object deserialize(byte[] serializedBytes) {
    Object deserializedObject = serializedBytes;
    // This is a debugging method so ignore all exceptions like
    // ClassNotFoundException
    try {
      if (this.valueIsObject == 0x00) {
        deserializedObject = serializedBytes;
      } else {
        deserializedObject = EntryEventImpl.deserialize(serializedBytes);
      }
    } catch (Exception e) {
    }
    return deserializedObject;
  }

  // / Conflatable interface methods ///

  /**
   * Determines whether or not to conflate this message. This method will answer
   * true IFF the message's operation is AFTER_UPDATE and its region has enabled
   * are conflation. Otherwise, this method will answer false. Messages whose
   * operation is AFTER_CREATE, AFTER_DESTROY, AFTER_INVALIDATE or
   * AFTER_REGION_DESTROY are not conflated.
   * 
   * @return Whether to conflate this message
   */
  public boolean shouldBeConflated() {
    // If the message is an update, it may be conflatable. If it is a
    // create, destroy, invalidate or destroy-region, it is not conflatable.
    // Only updates are conflated.
    return isUpdate();
  }

  public String getRegionToConflate() {
    return this.regionPath;
  }

  public Object getKeyToConflate() {
    return this.key;
  }

  public Object getValueToConflate() {
    return this.value;
  }

  public void setLatestValue(Object value) {
    this.value = (byte[])value;
  }

  // / End Conflatable interface methods ///

  /**
   * Returns whether this <code>GatewayEvent</code> represents an update.
   * 
   * @return whether this <code>GatewayEvent</code> represents an update
   */
  protected boolean isUpdate() {
    // This event can be in one of three states:
    // - in memory primary (initialized)
    // - in memory secondary (not initialized)
    // - evicted to disk, read back in (initialized)
    // In the first case, both the operation and action are set.
    // In the second case, only the operation is set.
    // In the third case, only the action is set.
    return this.operation == null ? this.action == UPDATE_ACTION
        : this.operation == EnumListenerEvent.AFTER_UPDATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a create.
   * 
   * @return whether this <code>GatewayEvent</code> represents a create
   */
  protected boolean isCreate() {
    // See the comment in isUpdate() for additional details
    return this.operation == null ? this.action == CREATE_ACTION
        : this.operation == EnumListenerEvent.AFTER_CREATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a destroy.
   * 
   * @return whether this <code>GatewayEvent</code> represents a destroy
   */
  protected boolean isDestroy() {
    // See the comment in isUpdate() for additional details
    return this.operation == null ? this.action == DESTROY_ACTION
        : this.operation == EnumListenerEvent.AFTER_DESTROY;
  }

  /**
   * Initialize the unique identifier for this event. This id is used by the
   * receiving <code>Gateway</code> to keep track of which events have been
   * processed. Duplicates can be dropped.
   */
  private void initializeId() {
    // CS43_HA
    this.id = this.entryEvent.getEventId();
    // TODO:ASIF :Once stabilized remove the check below
    if (this.id == null) {
      throw new IllegalStateException(
          LocalizedStrings.GatewayEventImpl_NO_EVENT_ID_IS_AVAILABLE_FOR_THIS_GATEWAY_EVENT
              .toLocalizedString());
    }

  }

  /**
   * Initialize this instance. Get the useful parts of the input operation and
   * event.
   * 
   * @throws IOException
   */
  public void initialize() throws IOException {
    // Set key
    // System.out.println("this._entryEvent: " + this._entryEvent);
    // System.out.println("this._entryEvent.getKey(): " +
    // this._entryEvent.getKey());
    if (this.key != TOKEN_UN_INITIALIZED) {
      // We have already initialized, or initialized elsewhere. Lets return.
      return;
    }
    this.key = this.entryEvent.getKey();

    // Set the value to be a byte[] representation of either the value or
    // substituteValue (if set).
    if (this.substituteValue == null) {
      // If the value is already serialized, use it.
      this.valueIsObject = 0x01;
      SerializedCacheValue serializedNewValue = this.entryEvent
          .getSerializedNewValue();
  
      if (serializedNewValue == null) {
        if (this.entryEvent.getCachedSerializedNewValue() != null) {
          this.value = this.entryEvent.getCachedSerializedNewValue();
        } else {
        Object newValue = this.entryEvent.getRawNewValue();
        if (newValue instanceof byte[]) {
          // The value is byte[]. Set _valueIsObject flag to 0x00 (not an object)
          this.value = (byte[])newValue;
          this.valueIsObject = 0x00;
        } else {
          // The value is an object. Serialize it.
          isSerializingValue.set(Boolean.TRUE);
          this.value = CacheServerHelper.serialize(newValue);
          isSerializingValue.set(Boolean.FALSE);
          this.entryEvent.setCachedSerializedNewValue(this.value);
        }
        }
      } else {
        this.value = serializedNewValue.getSerializedValue();
      }
    } else {
      // The substituteValue is set. Use it.
      if (this.substituteValue instanceof byte[]) {
        // The substituteValue is byte[]. Set valueIsObject flag to 0x00 (not an object)
        this.value = (byte[]) this.substituteValue;
        this.valueIsObject = 0x00;
      } else if (this.substituteValue == TOKEN_NULL) {
        // The substituteValue represents null. Set the value to null.
        this.value = null;
        this.valueIsObject = 0x01;
      } else {
        // The substituteValue is an object. Serialize it.
        isSerializingValue.set(Boolean.TRUE);
        this.value = CacheServerHelper.serialize(this.substituteValue);
        isSerializingValue.set(Boolean.FALSE);
        this.entryEvent.setCachedSerializedNewValue(this.value);
        this.valueIsObject = 0x01;
      }
    }

    // Set the callback arg
    this.callbackArgument = (GatewaySenderEventCallbackArgument)this.entryEvent
        .getRawCallbackArgument();

    // Initialize the action and number of parts (called after _callbackArgument
    // is set above)
    initializeAction(this.operation);
    
    //initialize the operation detail 
    initializeOperationDetail(this.entryEvent.getOperation());
    
    setShadowKey(entryEvent.getTailKey());
    
    // The entry event is no longer necessary. Null it so it can be GCed.
    this.entryEvent = null;
  }

  /**
   * Initialize this event's action and number of parts
   * 
   * @param operation
   *          The operation from which to initialize this event's action and
   *          number of parts
   */
  protected void initializeAction(EnumListenerEvent operation) {
    if (operation == EnumListenerEvent.AFTER_CREATE) {
      // Initialize after create action
      this.action = CREATE_ACTION;

      // Initialize number of parts
      // part 1 = action
      // part 2 = posDup flag
      // part 3 = regionName
      // part 4 = eventId
      // part 5 = key
      // part 6 = value (create and update only)
      // part 7 = whether callbackArgument is non-null
      // part 8 = callbackArgument (if non-null)
      // part 9 = versionTimeStamp;
      this.numberOfParts = (this.callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_UPDATE) {
      // Initialize after update action
      this.action = UPDATE_ACTION;

      // Initialize number of parts
      this.numberOfParts = (this.callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_DESTROY) {
      // Initialize after destroy action
      this.action = DESTROY_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this.numberOfParts = (this.callbackArgument == null) ? 7 : 8;
    } else if (operation == EnumListenerEvent.TIMESTAMP_UPDATE) {
      // Initialize after destroy action
      this.action = VERSION_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this.numberOfParts = (this.callbackArgument == null) ? 7 : 8;
    }
  }
  
  private void initializeOperationDetail(Operation operation) {
    if (operation.isLocalLoad()) {
      operationDetail = OP_DETAIL_LOCAL_LOAD;
    } else if (operation.isNetLoad()) {
      operationDetail = OP_DETAIL_NET_LOAD;
    } else if (operation.isPutAll()) {
      operationDetail = OP_DETAIL_PUTALL;
    } else if (operation.isRemoveAll()) {
      operationDetail = OP_DETAIL_REMOVEALL;
    } else {
      operationDetail = OP_DETAIL_NONE;
    }
  }

  public EventID getEventId() {
    return this.id;
  }

  /**
   * Return the EventSequenceID of the Event
   * @return    EventSequenceID
   */
  public EventSequenceID getEventSequenceID() {
    return new EventSequenceID(id.getMembershipID(), id.getThreadID(), id
        .getSequenceID());
  }
  
  public long getVersionTimeStamp() {
    return this.versionTimeStamp;
  }
  
  public int getSizeInBytes() {
    // Calculate the size of this event. This is used for overflow to disk.

    // The sizes of the following variables are calculated:
    //
    // - the value (byte[])
    // - the original callback argument (Object)
    // - primitive and object instance variable references
    //
    // The sizes of the following variables are not calculated:

    // - the key because it is a reference
    // - the region and regionName because they are references
    // - the operation because it is a reference
    // - the entry event because it is nulled prior to calling this method

    // The size of instances of the following internal datatypes were estimated
    // using a NullDataOutputStream and hardcoded into this method:

    // - the id (an instance of EventId)
    // - the callbackArgument (an instance of GatewayEventCallbackArgument)

    int size = 0;

    // Add this event overhead
    size += Sizeable.PER_OBJECT_OVERHEAD;

    // Add object references
    // _id reference = 4 bytes
    // _region reference = 4 bytes
    // _regionName reference = 4 bytes
    // _key reference = 4 bytes
    // _callbackArgument reference = 4 bytes
    // _operation reference = 4 bytes
    // _entryEvent reference = 4 bytes
    size += 28;

    // Add primitive references
    // int _action = 4 bytes
    // int _numberOfParts = 4 bytes
    // byte _valueIsObject = 1 byte
    // boolean _possibleDuplicate = 1 byte
    // int bucketId = 4 bytes
    // long shadowKey = 8 bytes
    // long creationTime = 8 bytes
    size += 30;

    // Add the id (an instance of EventId)
    // The hardcoded value below was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 56;

    // The value (a byte[])
    if (this.value != null) {
      size += CachedDeserializableFactory.calcMemSize(this.value);
    }

    // The callback argument (a GatewayEventCallbackArgument wrapping an Object
    // which is the original callback argument)
    // The hardcoded value below represents the GatewayEventCallbackArgument
    // and was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 194;
    // The sizeOf call gets the size of the input callback argument.
    size += Sizeable.PER_OBJECT_OVERHEAD + sizeOf(getCallbackArgument());

    // the version timestamp
    size += 8;
    
    return size;
  }

  private int sizeOf(Object obj) {
    int size = 0;
    if (obj == null) {
      return size;
    }
    if (obj instanceof String) {
      size = ObjectSizer.DEFAULT.sizeof(obj);
    } else if (obj instanceof Integer) {
      size = 4; // estimate
    } else if (obj instanceof Long) {
      size = 8; // estimate
    } else {
      size = CachedDeserializableFactory.calcMemSize(obj)
          - Sizeable.PER_OBJECT_OVERHEAD;
    }
    return size;
  }


  // Asif: If the GatewayEvent serializes to a node where the region itself may
  // not be present or the
  // region is not created yet , and if the gateway event queue is persistent,
  // then even if
  // we try to set the region in the fromData , we may still get null. Though
  // the product is
  // not using this method anywhere still not comfortable changing the Interface
  // so
  // modifying the implementation a bit.

  public Region<?, ?> getRegion() {
    // The region will be null mostly for the other node where the gateway event
    // is serialized
    return this.region != null ? this.region : CacheFactory.getAnyInstance()
        .getRegion(this.regionPath);
  }

  public int getBucketId() {
    return bucketId;
  }

  /**
   * @param tailKey
   *          the tailKey to set
   */
  public void setShadowKey(Long tailKey) {
    this.shadowKey = tailKey;
  }

  /**
   * @return the tailKey
   */
  public Long getShadowKey() {
    return this.shadowKey;
  }

  @Override
  public Version[] getSerializationVersions() {
    // TODO Auto-generated method stub
    return null;
  }

}
