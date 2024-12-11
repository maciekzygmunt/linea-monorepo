# Solidity API

## L1MessageManagerV1

### INBOX_STATUS_UNKNOWN

```solidity
uint8 INBOX_STATUS_UNKNOWN
```

The 2 legacy status constants for message statuses.

### INBOX_STATUS_RECEIVED

```solidity
uint8 INBOX_STATUS_RECEIVED
```

### OUTBOX_STATUS_UNKNOWN

```solidity
uint8 OUTBOX_STATUS_UNKNOWN
```

The 3 legacy status constants for message statuses.

### OUTBOX_STATUS_SENT

```solidity
uint8 OUTBOX_STATUS_SENT
```

### OUTBOX_STATUS_RECEIVED

```solidity
uint8 OUTBOX_STATUS_RECEIVED
```

### outboxL1L2MessageStatus

```solidity
mapping(bytes32 => uint256) outboxL1L2MessageStatus
```

_DEPRECATED in favor of the rollingHashes mapping on the L1MessageManager for L1 to L2 messaging._

### inboxL2L1MessageStatus

```solidity
mapping(bytes32 => uint256) inboxL2L1MessageStatus
```

_Mapping to store L2->L1 message hashes status.
messageHash => messageStatus (0: unknown, 1: received).
For the most part this has been deprecated. This is only used for messages received pre-AlphaV2._

### _updateL2L1MessageStatusToClaimed

```solidity
function _updateL2L1MessageStatusToClaimed(bytes32 _messageHash) internal
```

Update the status of L2->L1 message when a user claims a message on L1.

_The L2->L1 message is removed from storage.
Due to the nature of the rollup, we should not get a second entry of this._

#### Parameters

| Name | Type | Description |
| ---- | ---- | ----------- |
| _messageHash | bytes32 | Hash of the message. |
