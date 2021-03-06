package br.org.cip.CRMMock.Fabric;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.hyperledger.fabric.sdk.BlockEvent.TransactionEvent;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.EventHub;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.protobuf.ByteString;

import br.org.cip.CRMMock.model.Feriado;
import br.org.cip.CRMMock.model.UserVO;

@Service
public class ChaincodeService{

	private static final Logger log = LoggerFactory.getLogger(ChaincodeService.class);

	private static final String USERAPPPATH = System.getenv("WEBAPP_USERAPPPATH");//"/usr/local/minerva/";//"/usr/local/minerva/" ou ""

	static HFCAClient caClient;// = getHfCaClient("http://localhost:7054", null);
	static UserVO admin;// = getAdmin(caClient);
	static UserVO appUser;
	// private static UserVO appUser = getUser(caClient, admin, "hfuser");
	static HFClient client;// = getHfClient();
	static Channel channel;// = getChannel(client);

	public ChaincodeService() throws Exception {
		caClient = getHfCaClient("http://ca.cipbancos.org.br:7054", null);//ca.cipbancos.org.br:7054//10.150.162.190
		admin = getAdmin(caClient);
		appUser = getUser(caClient, admin, "hfuser");
		client = getHfClient();
		client.setUserContext(appUser);
		channel = getChannel(client);
	}

	/**
	 * Initialize and get HF channel
	 *
	 * @param client
	 *            The HFC client
	 * @return Initialized channel
	 * @throws InvalidArgumentException
	 * @throws TransactionException
	 */
	static Channel getChannel(HFClient client) {
		// initialize channel
		// peer name and endpoint in fabcar network
		try {
			String peerEnv = System.getenv("WEBAPP_PEER");
			String portEnv = System.getenv("WEBAPP_PEER_PORT");
			String portEnvEventHub = System.getenv("WEBAPP_PORT_EVENTHUB");
			Peer peer = client.newPeer(peerEnv, "grpc://" + peerEnv + ":" + portEnv);//("peer1.cipbancos.org.br", "grpc://peer1.cipbancos.org.br:7051");//peer0.cipbancos.org.br
			// eventhub name and endpoint in fabcar network
			EventHub eventHub = client.newEventHub("eventhub01", "grpc://"+ peerEnv + ":" + portEnvEventHub);//peer0.cipbancos.org.br
			// orderer name and endpoint in fabcar network
			Orderer orderer = client.newOrderer("orderer.cipbancos.org.br", "grpc://orderer.cipbancos.org.br:7050");//orderer.cipbancos.org.br//10.150.162.190
			// channel name in network
			Channel channel = client.newChannel("mychannel");
			channel.addPeer(peer);
			channel.addEventHub(eventHub);
			channel.addOrderer(orderer);
			channel.initialize();
			return channel;
		} catch (InvalidArgumentException | TransactionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return channel;
	}

	/**
	 * Create new HLF client
	 *
	 * @return new HLF client instance. Never null.
	 * @throws CryptoException
	 * @throws InvalidArgumentException
	 */
	static HFClient getHfClient() {
		// initialize default cryptosuite
		CryptoSuite cryptoSuite;
		try {
			cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
			// setup the client
			HFClient client = HFClient.createNewInstance();
			client.setCryptoSuite(cryptoSuite);
			return client;
		} catch (IllegalAccessException | InstantiationException | ClassNotFoundException | CryptoException
				| InvalidArgumentException | NoSuchMethodException | InvocationTargetException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/**
	 * Register and enroll user with userId. If UserVO object with the name already
	 * exist on fs it will be loaded and registration and enrollment will be
	 * skipped.
	 * 
	 * @param user
	 * @param caClient
	 *
	 * @param caClient
	 *            The fabric-ca client.
	 * @param registrar
	 *            The registrar to be used.
	 * @param userId
	 *            The user id.
	 * @return UserVO instance with userId, affiliation,mspId and enrollment set.
	 * @throws Exception
	 */
	static UserVO getUser(HFCAClient caClient, UserVO registrar, String userId) {
		try {
			UserVO user = tryDeserialize(userId);
			if (user == null) {
				RegistrationRequest rr = new RegistrationRequest(userId, "cip");
				String enrollmentSecret = caClient.register(rr, registrar);
				Enrollment enrollment = caClient.enroll(userId, enrollmentSecret);
				user = new UserVO(userId, "cip", "CIPMSP", enrollment);
				serialize(user);
			}
			return user;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	/**
	 * Enroll admin into fabric-ca using {@code admin/adminpw} credentials. If
	 * UserVO object already exist serialized on fs it will be loaded and new
	 * enrollment will not be executed.
	 *
	 * @param caClient
	 *            The fabric-ca client
	 * @return UserVO instance with userid, affiliation, mspId and enrollment set
	 * @throws Exception
	 */
	static UserVO getAdmin(HFCAClient caClient) {
		UserVO admin;
		try {
			admin = tryDeserialize("admin");
			if (admin == null) {
				Enrollment adminEnrollment = caClient.enroll("admin", "adminpw");
				admin = new UserVO("admin", "cip", "CIPMSP", adminEnrollment);
				serialize(admin);
			}
			return admin;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get new fabic-ca client
	 *
	 * @param caUrl
	 *            The fabric-ca-server endpoint url
	 * @param caClientProperties
	 *            The fabri-ca client properties. Can be null.
	 * @return new client instance. never null.
	 * @throws Exception
	 */
	static HFCAClient getHfCaClient(String caUrl, Properties caClientProperties) {
		try {
			CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
			HFCAClient caClient = HFCAClient.createNewInstance(caUrl, caClientProperties);
			caClient.setCryptoSuite(cryptoSuite);
			return caClient;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// user serialization and deserialization utility functions
	// files are stored in the base directory

	/**
	 * Serialize UserVO object to file
	 *
	 * @param user
	 *            The object to be serialized
	 * @throws IOException
	 */
	static void serialize(UserVO user) throws IOException {
		try (ObjectOutputStream oos = new ObjectOutputStream(
				Files.newOutputStream(Paths.get(USERAPPPATH + user.getName() + ".jso")))) {
			oos.writeObject(user);
		}
	}

	/**
	 * Deserialize UserVO object from file
	 *
	 * @param name
	 *            The name of the user. Used to build file name ${name}.jso
	 * @return
	 * @throws Exception
	 */
	static UserVO tryDeserialize(String name) throws Exception {
		if (Files.exists(Paths.get(USERAPPPATH + name + ".jso"))) {
			return deserialize(name);
		}
		return null;
	}

	static UserVO deserialize(String name) throws Exception {
		try (ObjectInputStream decoder = new ObjectInputStream(Files.newInputStream(Paths.get(USERAPPPATH + name + ".jso")))) {
			return (UserVO) decoder.readObject();
		}
	}

	public List<Feriado> getAllFeriados() throws InvalidArgumentException, ProposalException {
		QueryByChaincodeRequest qpr = client.newQueryProposalRequest();
		ChaincodeID minervaCCId = ChaincodeID.newBuilder().setName("minerva-app").build();
		qpr.setChaincodeID(minervaCCId);
		qpr.setFcn("queryAllFeriado");

		Collection<ProposalResponse> responses = channel.queryByChaincode(qpr);
		try {
			List<Feriado> feriados = new ArrayList<Feriado>();
			for (ProposalResponse pres : responses) {
				if (!pres.isVerified() || pres.getStatus() == ChaincodeResponse.Status.FAILURE || pres.getStatus() == ChaincodeResponse.Status.UNDEFINED) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					log.error("erro: {}, {}", pres.getStatus().getStatus(), payload.toByteArray());
					throw new RuntimeException();
				}
				if (pres.isVerified() && pres.getStatus() == ChaincodeResponse.Status.SUCCESS) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					try (JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(payload.toByteArray()))) {
						JsonArray arr = jsonReader.readArray();
						for (int i = 0; i < arr.size(); i++) {
							JsonObject rec = arr.getJsonObject(i);
							Feriado feriadoRecorded = generateFeriadoFromJsonArray(rec);
							feriados.add(feriadoRecorded);
						}
						log.debug("Feriados consultados.");
					}					
				}
			}
			return feriados;
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Erro ao consultar todos os Feriados.");
			log.error("Stacktrace: {}", e);
			throw new RuntimeException();
		}
	}

	private Feriado generateFeriadoFromJsonArray(JsonObject rec) {
		long key = Long.parseLong(rec.get("Key").toString().substring(1, rec.get("Key").toString().length() - 1));
		String tipoRequisicao = rec.get("Record").asJsonObject().getString("tipoRequisicao");
		String data = rec.get("Record").asJsonObject().getString("data");
		String[] fields = data.split("/");
		data = fields[2] + "/" + fields[1] + "/" + fields[0];
		String descricao = rec.get("Record").asJsonObject().getString("descricao");
		String situacao = rec.get("Record").asJsonObject().getString("situacao");
		String tipoFeriado = rec.get("Record").asJsonObject().getString("tipoFeriado");
		Feriado feriado = new Feriado();
		feriado.setData(data);
		feriado.setDescricao(descricao);
		feriado.setId(key);
		feriado.setSituacao(situacao);
		feriado.setTipoFeriado(tipoFeriado);
		feriado.setTipoRequisicao(tipoRequisicao);
		return feriado;
	}

	private Feriado generateFeriado(JsonObject rec) {
		String tipoRequisicao = rec.asJsonObject().getString("tipoRequisicao");
		String data = rec.asJsonObject().getString("data");
		String[] fields = data.split("/");
		String key = fields[0] + fields[1] + fields[2];
		data = fields[2] + "/" + fields[1] + "/" + fields[0];
		String descricao = rec.asJsonObject().getString("descricao");
		String situacao = rec.asJsonObject().getString("situacao");
		String tipoFeriado = rec.asJsonObject().getString("tipoFeriado");
		Feriado feriado = new Feriado();
		feriado.setData(data);
		feriado.setDescricao(descricao);
		feriado.setId(Long.parseLong(key));
		feriado.setSituacao(situacao);
		feriado.setTipoFeriado(tipoFeriado);
		feriado.setTipoRequisicao(tipoRequisicao);
		return feriado;
	}

	public long recordFeriado(String data, String descricao, String situacao, String tipoFeriado, String tipoRequisicao) {

		TransactionProposalRequest req = client.newTransactionProposalRequest();
		ChaincodeID cid = ChaincodeID.newBuilder().setName("minerva-app").build();
		req.setChaincodeID(cid);
		req.setFcn("incluir");
		String[] fields = data.split("/");
		data = fields[2] + "/" + fields[1] + "/" + fields[0];
		req.setArgs(new String[] { data, descricao, situacao, tipoFeriado, tipoRequisicao });
		Collection<ProposalResponse> resps;
		try {
			resps = channel.sendTransactionProposal(req);
			Long key = null;
			for (ProposalResponse pres : resps) {
				if (!pres.isVerified() || pres.getStatus() == ChaincodeResponse.Status.FAILURE || pres.getStatus() == ChaincodeResponse.Status.UNDEFINED) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					log.error("erro: {}, {}", pres.getStatus().getStatus(), payload.toByteArray());
					throw new RuntimeException();
				}
				if (pres.isVerified() && pres.getStatus() == ChaincodeResponse.Status.SUCCESS) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					try (JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(payload.toByteArray()))) {
						JsonObject rec = jsonReader.readObject();
						key = Long.parseLong(rec.getString("Key"));
					}					
				}
			}
			Future<TransactionEvent> future = channel.sendTransaction(resps);
			future.get();
			return key;
		} catch (ProposalException | InvalidArgumentException | InterruptedException | ExecutionException e) {
			e.printStackTrace();
			log.error("Erro ao mudar o Feriado.");
			log.error("Stacktrace: {}", e);
			throw new RuntimeException();
		}
	}

	public long changeFeriado(String data, String descricao, String situacao, String tipoFeriado,	String tipoRequisicao) {
		TransactionProposalRequest req = client.newTransactionProposalRequest();
		ChaincodeID cid = ChaincodeID.newBuilder().setName("minerva-app").build();
		req.setChaincodeID(cid);
		req.setFcn("alterar");
		String[] fields = data.split("/");
		data = fields[2] + "/" + fields[1] + "/" + fields[0];
		req.setArgs(new String[] { data, descricao, situacao, tipoFeriado, tipoRequisicao });
		Collection<ProposalResponse> resps;
		try {
			resps = channel.sendTransactionProposal(req);
			Long key = null;
			for (ProposalResponse pres : resps) {
				if (!pres.isVerified() || pres.getStatus() == ChaincodeResponse.Status.FAILURE || pres.getStatus() == ChaincodeResponse.Status.UNDEFINED) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					log.error("erro: {}, {}", pres.getStatus().getStatus(), payload.toByteArray());
					throw new RuntimeException();
				}
				if (pres.isVerified() && pres.getStatus() == ChaincodeResponse.Status.SUCCESS) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					try (JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(payload.toByteArray()))) {
						JsonObject rec = jsonReader.readObject();
						key = Long.parseLong(rec.getString("Key"));
					}					
				}
			}
			Future<TransactionEvent> future = channel.sendTransaction(resps);
			future.get();
			return key;
		} catch (ProposalException | InvalidArgumentException | InterruptedException | ExecutionException e) {
			e.printStackTrace();
			log.error("Erro ao mudar o Feriado.");
			log.error("Stacktrace: {}", e);
			throw new RuntimeException();
		}
	}

	public Feriado getFeriado(String feriadoId) {
		QueryByChaincodeRequest qpr = client.newQueryProposalRequest();
		ChaincodeID fabcarCCId = ChaincodeID.newBuilder().setName("minerva-app").build();
		qpr.setChaincodeID(fabcarCCId);
		qpr.setFcn("consultar");
		qpr.setArgs(new String[] { feriadoId });

		Collection<ProposalResponse> responses;
		try {
			responses = channel.queryByChaincode(qpr);
			Feriado feriadoRecorded = null;
			for (ProposalResponse pres : responses) {
				if (!pres.isVerified() || pres.getStatus() == ChaincodeResponse.Status.FAILURE || pres.getStatus() == ChaincodeResponse.Status.UNDEFINED) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					log.error("erro: {}, {}", pres.getStatus().getStatus(), payload.toByteArray());
					throw new RuntimeException();
				}
				if (pres.isVerified() && pres.getStatus() == ChaincodeResponse.Status.SUCCESS) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					try (JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(payload.toByteArray()))) {
						JsonObject rec = jsonReader.readObject();
						feriadoRecorded = generateFeriado(rec);
					}					
				}
			}
			return feriadoRecorded;
		} catch (InvalidArgumentException | ProposalException e) {
			e.printStackTrace();
			log.error("Erro ao consultar o Feriado.");
			log.error("Stacktrace: {}", e);
			throw new RuntimeException();
		}
	}

	public void changeFeriadoSituacao(String key, String action) {
		TransactionProposalRequest req = client.newTransactionProposalRequest();
		ChaincodeID cid = ChaincodeID.newBuilder().setName("minerva-app").build();
		req.setChaincodeID(cid);
		req.setFcn("mudarSituacao");
		req.setArgs(new String[] { key, action });
		Collection<ProposalResponse> resps;
		try {
			resps = channel.sendTransactionProposal(req);
			for (ProposalResponse pres : resps) {
				if (!pres.isVerified() || pres.getStatus() == ChaincodeResponse.Status.FAILURE || pres.getStatus() == ChaincodeResponse.Status.UNDEFINED) {
					ByteString payload = pres.getProposalResponse().getResponse().getPayload();
					log.error("erro: {}, {}", pres.getStatus().getStatus(), payload.toByteArray());
					throw new RuntimeException();
				}
			}
			Future<TransactionEvent> future = channel.sendTransaction(resps);
			future.get();
		} catch (ProposalException | InvalidArgumentException | InterruptedException | ExecutionException e) {
			e.printStackTrace();
			log.error("Erro ao mudar a situacao do Feriado.");
			log.error("Stacktrace: {}", e);
			throw new RuntimeException(e);
		}
	}
}
