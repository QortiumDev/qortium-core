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
            // get all online account indices from block
            ConciseSet onlineAccountIndices = BlockTransformer.decodeOnlineAccounts(blockData.getEncodedOnlineAccounts());

            // get online self-shares from the online accounts on the block
            List<RewardShareData> onlineRewardShares = repository.getAccountRepository().getSelfSharesByIndexes(onlineAccountIndices.toArray());

            // online timestamp for block
            long onlineTimestamp = blockData.getOnlineAccountsTimestamp();
            Set<DecodedOnlineAccountData> onlineAccounts = new HashSet<>();

            // all minting group member addresses
            List<String> mintingGroupAddresses
                = Groups.getAllMembers(
                    repository.getGroupRepository(),
                    Groups.getGroupIdsToMint(BlockChain.getInstance(), blockData.getHeight())
                );

            // all names, indexed by address
            Map<String, String> nameByAddress
                = repository.getNameRepository()
                    .getAllNames().stream()
                    .collect(Collectors.toMap(NameData::getOwner, NameData::getName));

            // all accounts at level 1 or higher, indexed by address
            Map<String, Integer> levelByAddress
                = repository.getAccountRepository().getAddressLevelPairings(1).stream()
                    .collect(Collectors.toMap(AddressLevelPairing::getAddress, AddressLevelPairing::getLevel));

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
                        levelByAddress.get(minter)
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
