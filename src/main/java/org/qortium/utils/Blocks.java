package org.qortium.utils;

import io.druid.extendedset.intset.ConciseSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AddressLevelPairing;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.DecodedOnlineAccountData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.naming.NameData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.block.BlockTransformer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class Blocks
 *
 * Methods for block related logic.
 */
public class Blocks {

    private static final Logger LOGGER = LogManager.getLogger(Blocks.class);

    /**
     * Get Decode Online Accounts For Block
     *
     * @param repository the data repository
     * @param blockData the block data
     *
     * @return the online accounts set to the block
     *
     * @throws DataException
     */
    public static Set<DecodedOnlineAccountData> getDecodedOnlineAccountsForBlock(Repository repository, BlockData blockData) throws DataException {
        try {
            // Prefer the local (non-consensus) per-block online-accounts index, captured at
            // block-processing time. It stores absolute reward-share public keys, which stay
            // resolvable forever — unlike the block's positional online-account indices, which can
            // no longer be resolved once the self-share set changes (accounts added/removed).
            List<byte[]> onlineRewardSharePublicKeys
                = repository.getBlockRepository().getOnlineRewardSharePublicKeys(blockData.getHeight());

            List<RewardShareData> onlineRewardShares;
            if (onlineRewardSharePublicKeys != null) {
                // Indexed locally: resolve each stable public key back to its reward-share.
                onlineRewardShares = new ArrayList<>(onlineRewardSharePublicKeys.size());
                for (byte[] rewardSharePublicKey : onlineRewardSharePublicKeys) {
                    RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
                    // Skip reward-shares that have since been cancelled and no longer resolve.
                    if (rewardShareData != null)
                        onlineRewardShares.add(rewardShareData);
                }
            } else {
                // Legacy fallback (block not indexed by this node, e.g. processed before upgrade):
                // positional index decode, only correct while the self-share set is unchanged.
                ConciseSet onlineAccountIndices = BlockTransformer.decodeOnlineAccounts(blockData.getEncodedOnlineAccounts());
                onlineRewardShares = repository.getAccountRepository().getSelfSharesByIndexes(onlineAccountIndices.toArray());
            }

            if (onlineRewardShares == null)
                return new HashSet<>(0);

            // online timestamp for block
            long onlineTimestamp = blockData.getOnlineAccountsTimestamp();
            Set<DecodedOnlineAccountData> onlineAccounts = new HashSet<>();

            // all minting group member addresses
            List<String> mintingGroupAddresses
                = Groups.getAllMembers(
                    repository.getGroupRepository(),
                    Groups.getGroupIdsToMint(BlockChain.getInstance(), blockData.getHeight())
                );

            // all names, indexed by address. An account may own more than one name, so keep the
            // first per owner instead of letting Collectors.toMap throw on duplicate keys (which
            // the catch below would otherwise swallow into an empty result).
            Map<String, String> nameByAddress
                = repository.getNameRepository()
                    .getAllNames().stream()
                    .collect(Collectors.toMap(NameData::getOwner, NameData::getName, (existing, ignored) -> existing));

            // all accounts at level 1 or higher, indexed by address (merge defensively).
            Map<String, Integer> levelByAddress
                = repository.getAccountRepository().getAddressLevelPairings(1).stream()
                    .collect(Collectors.toMap(AddressLevelPairing::getAddress, AddressLevelPairing::getLevel, (existing, ignored) -> existing));

            // for each self-share where the minter is online,
            // construct the data object and add it to the return list
            for (RewardShareData onlineRewardShare : onlineRewardShares) {
                String minter = onlineRewardShare.getMinter();
                DecodedOnlineAccountData onlineAccountData
                    = new DecodedOnlineAccountData(
                        onlineTimestamp,
                        minter,
                        onlineRewardShare.getRecipient(),
                        onlineRewardShare.getSharePercent(),
                        mintingGroupAddresses.contains(minter),
                        nameByAddress.get(minter),
                        // getAddressLevelPairings(1) only returns level >= 1, so a level-0 online
                        // account is absent; default to 0 rather than unboxing null into an NPE
                        // (which the catch below would otherwise swallow into an empty result).
                        levelByAddress.getOrDefault(minter, 0)
                        );

                onlineAccounts.add(onlineAccountData);
            }

            return onlineAccounts;
        } catch (DataException e) {
            throw e;
        } catch (Exception e ) {
            LOGGER.error(e.getMessage(), e);

            return new HashSet<>(0);
        }
    }
}
