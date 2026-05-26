package org.qortium.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.ApiError;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.block.BlockChain;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.utils.Amounts;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;

@Path("/stats")
@Tag(name = "Stats")
public class StatsResource {

	private static final Logger LOGGER = LogManager.getLogger(StatsResource.class);


	@Context
	HttpServletRequest request;

	@GET
	@Path("/supply/circulating")
	@Operation(
		summary = "Fetch circulating native asset supply",
		responses = {
			@ApiResponse(
					description = "circulating supply of native asset",
					content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", format = "number"))
			)
		}
	)
	public BigDecimal circulatingSupply() {
		long total = 0L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();

			List<BlockChain.RewardByHeight> rewardsByHeight = BlockChain.getInstance().getBlockRewardsByHeight();
			int rewardIndex = rewardsByHeight.size() - 1;
			BlockChain.RewardByHeight rewardInfo = rewardsByHeight.get(rewardIndex);

			for (int height = currentHeight; height > 1; --height) {
				if (height < rewardInfo.height) {
					--rewardIndex;
					rewardInfo = rewardsByHeight.get(rewardIndex);
				}

				total += rewardInfo.reward;
			}

			return Amounts.toBigDecimal(total);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
