package com.mycorp;

import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mycorp.support.CorreoElectronico;
import com.mycorp.support.DatosCliente;
import com.mycorp.support.MensajeriaService;
import com.mycorp.support.Poliza;
import com.mycorp.support.PolizaBasicoFromPolizaBuilder;
import com.mycorp.support.Ticket;
import com.mycorp.support.ValueCode;

import portalclientesweb.ejb.interfaces.PortalClientesWebEJBRemote;
import util.datos.DatosPersonales;
import util.datos.DetallePoliza;
import util.datos.PolizaBasico;
import util.datos.UsuarioAlta;

@Service
public class ZendeskService {

    private static final Logger LOG = LoggerFactory.getLogger( ZendeskService.class );

    private static final String ESCAPED_LINE_SEPARATOR = "\\n";
    private static final String ESCAPE_ER = "\\";
    private static final String HTML_BR = "<br/>";
    @Value("#{envPC['zendesk.ticket']}")
    public String PETICION_ZENDESK= "";

    @Value("#{envPC['zendesk.token']}")
    public String TOKEN_ZENDESK= "";

    @Value("#{envPC['zendesk.url']}")
    public String URL_ZENDESK= "";

    @Value("#{envPC['zendesk.user']}")
    public String ZENDESK_USER= "";

    @Value("#{envPC['tarjetas.getDatos']}")
    public String TARJETAS_GETDATOS = "";

    @Value("#{envPC['cliente.getDatos']}")
    public String CLIENTE_GETDATOS = "";

    @Value("#{envPC['zendesk.error.mail.funcionalidad']}")
    public String ZENDESK_ERROR_MAIL_FUNCIONALIDAD = "";

    @Value("#{envPC['zendesk.error.destinatario']}")
    public String ZENDESK_ERROR_DESTINATARIO = "";

    private SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");


    /** The portalclientes web ejb remote. */
    @Autowired
    // @Qualifier("portalclientesWebEJB")
    private PortalClientesWebEJBRemote portalclientesWebEJBRemote;

    /** The rest template. */
    @Autowired
    @Qualifier("restTemplateUTF8")
    private RestTemplate restTemplate;

    @Autowired
    @Qualifier( "emailService" )
    MensajeriaService emailService;
    
	private VelocityContext ctx;		// contexto de Velocity que se usara para el merge con las plantillas
	private Template tplDatosUsuario;	// plantilla de datos Usuario
	private Template tplDatosBravo;		// plantilla de datos Bravo
	private Writer writer;				// Writer para almacenar la salida del merge de contexto y plantilla

    /**
     * <p>Crea un ticket en Zendesk, recolectando información de distintas fuentes:
     * <li>parametros de entrada (usuarioAlta y userAgent)
     * <li>servicio externo de tarjetas
     * <li>servicio externo de polizas
     * <li>servicio externo de BRAVO
     * 
     * <p>Si se produce un error en la generación del ticket, se envia un mail
     * 
     * @param usuarioAlta
     * @param userAgent
     * @return String 
     */
    public String altaTicketZendesk(UsuarioAlta usuarioAlta, String userAgent){

        ObjectMapper mapper = new ObjectMapper();	// mapeador Jackson: Objetos <-> JSON
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        String idCliente = null;

        StringBuilder clientName = new StringBuilder();
        
        try {
			initVelocity();
		} catch (Exception e) {
			LOG.error("Error on Velocity initialization", e);
		}

        //===============================================================
        // recolecta de DATOS DE ENTRADA: usuarioAlta & userAgent
        
        boolean isPoliza = StringUtils.isNotBlank(usuarioAlta.getNumPoliza());
        ctx.put("isPoliza", isPoliza);
        // Añade los datos del formulario
        if(isPoliza){
        	ctx.put("numPoliza", usuarioAlta.getNumPoliza());
        	ctx.put("numDocAcreditativo", usuarioAlta.getNumDocAcreditativo());
        }else{
        	ctx.put("numTarjeta", usuarioAlta.getNumTarjeta());
        }
        ctx.put("tipoDocAcreditativo", usuarioAlta.getTipoDocAcreditativo());
        ctx.put("numDocAcreditativo", usuarioAlta.getNumDocAcreditativo());
        ctx.put("email", usuarioAlta.getEmail());
        ctx.put("numeroTelefono", usuarioAlta.getNumeroTelefono());
        ctx.put("userAgent", userAgent);

        //===============================================================
        // recolecta de datos de TARJETA y POLIZA de servicios externos

        StringBuilder datosServicio = new StringBuilder();
        // Obtiene el idCliente de la tarjeta
        if(StringUtils.isNotBlank(usuarioAlta.getNumTarjeta())){
            try{
                String urlToRead = TARJETAS_GETDATOS + usuarioAlta.getNumTarjeta();
                ResponseEntity<String> res = restTemplate.getForEntity( urlToRead, String.class);
                if(res.getStatusCode() == HttpStatus.OK){
                	idCliente = res.getBody();
                    clientName.append(idCliente);
                    datosServicio.append("Datos recuperados del servicio de tarjeta:").append(ESCAPED_LINE_SEPARATOR).append(mapper.writeValueAsString(idCliente));
                }
            }catch(Exception e)
            {
                LOG.error("Error al obtener los datos de la tarjeta", e);
            }
        }
        else if(StringUtils.isNotBlank(usuarioAlta.getNumPoliza())){
            try
            {
                Poliza poliza = new Poliza();
                poliza.setNumPoliza(Integer.valueOf(usuarioAlta.getNumPoliza()));
                poliza.setNumColectivo(Integer.valueOf(usuarioAlta.getNumDocAcreditativo()));
                poliza.setCompania(1);

                PolizaBasico polizaBasicoConsulta = new PolizaBasicoFromPolizaBuilder().withPoliza( poliza ).build();

                final DetallePoliza detallePolizaResponse = portalclientesWebEJBRemote.recuperarDatosPoliza(polizaBasicoConsulta);
                DatosPersonales tomador = detallePolizaResponse.getTomador();
                clientName.append(tomador.getNombre()).append(" ").append(tomador.getApellido1()).append(" ").append(tomador.getApellido2());
                idCliente = tomador.getIdentificador();
                datosServicio.append("Datos recuperados del servicio de tarjeta:").append(ESCAPED_LINE_SEPARATOR).append(mapper.writeValueAsString(detallePolizaResponse));
            }catch(Exception e)
            {
                LOG.error("Error al obtener los datos de la poliza", e);
            }
        }

        //===============================================================
        // recolecta de DATOS BRAVO de servicio externo
        
        try
        {
            // Obtenemos los datos del cliente
            DatosCliente cliente = restTemplate.getForObject("http://localhost:8080/test-endpoint", DatosCliente.class, idCliente);

            ctx.put("genTGrupoTmk", cliente.getGenTGrupoTmk());
            ctx.put("fechaNacimiento", formatter.format(formatter.parse(cliente.getFechaNacimiento())));

            List< ValueCode > tiposDocumentos = getTiposDocumentosRegistro();
            String genCTipoDDocumento = cliente.getGenCTipoDocumento().toString();
            List<String> tiposDocumentosCliente = new ArrayList<String>();
            for (ValueCode vc: tiposDocumentos) {
            	if (vc.getCode().equals(genCTipoDDocumento) ) {
            		tiposDocumentosCliente.add(vc.getValue());
            	}
            }
            ctx.put("tiposDocumentosCliente", tiposDocumentosCliente);
            ctx.put("numeroDocAcred", cliente.getNumeroDocAcred());

            String tipoCliente;
            switch (cliente.getGenTTipoCliente()) {
            case 1:
            	tipoCliente = "POTENCIAL";
                break;
            case 2:
            	tipoCliente = "REAL";
                break;
            case 3:
            	tipoCliente = "PROSPECTO";
                break;
            default: 
            	tipoCliente = "";  // TODO: Validar
            }
            ctx.put("tipoCliente", tipoCliente);
            ctx.put("genTStatus", cliente.getGenTStatus());
            ctx.put("idMotivoAlta", cliente.getIdMotivoAlta());
            ctx.put("fInactivoWeb", cliente.getfInactivoWeb() == null ? "SÍ" : "No");

        }catch(Exception e)
        {
            LOG.error("Error al obtener los datos en BRAVO del cliente", e);
        }

        //====================================================================
        // MERGE de plantillas
        
        String datosUsuarioStr = mergeTemplate(tplDatosUsuario, ctx);
        String datosBravoStr = mergeTemplate(tplDatosBravo, ctx);
        
        //====================================================================
        // composicion y GENERACION del TICKET. Envio de mail en caso de error

        String ticket = String.format(PETICION_ZENDESK, clientName.toString(), usuarioAlta.getEmail(), datosUsuarioStr + datosBravoStr +
                parseJsonBravo(datosServicio));
        ticket = ticket.replaceAll("["+ESCAPED_LINE_SEPARATOR+"]", " ");

        try(Zendesk zendesk = new Zendesk.Builder(URL_ZENDESK).setUsername(ZENDESK_USER).setToken(TOKEN_ZENDESK).build()){
            //Ticket
            Ticket petiZendesk = mapper.readValue(ticket, Ticket.class);
            zendesk.createTicket(petiZendesk);

        }catch(Exception e){
            LOG.error("Error al crear ticket ZENDESK", e);
            
            // Send email
            CorreoElectronico correo = new CorreoElectronico( Long.parseLong(ZENDESK_ERROR_MAIL_FUNCIONALIDAD), "es" )
                    .addParam(datosUsuarioStr.replaceAll(ESCAPE_ER+ESCAPED_LINE_SEPARATOR, HTML_BR))
                    .addParam(datosBravoStr.replaceAll(ESCAPE_ER+ESCAPED_LINE_SEPARATOR, HTML_BR));
            correo.setEmailA( ZENDESK_ERROR_DESTINATARIO );
            try
            {
                emailService.enviar( correo );
            }catch(Exception ex){
                LOG.error("Error al enviar mail", ex);
            }

        }

        return datosUsuarioStr + datosBravoStr;
    }

    public List< ValueCode > getTiposDocumentosRegistro() {
        return Arrays.asList( new ValueCode(), new ValueCode() ); // simulacion servicio externo
    }

    /**
     * Método para parsear el JSON de respuesta de los servicios de tarjeta/pÃ³liza
     *
     * @param resBravo
     * @return
     */
    private String parseJsonBravo(StringBuilder resBravo)
    {
        return resBravo.toString().replaceAll("[\\[\\]\\{\\}\\\"\\r]", "").replaceAll(ESCAPED_LINE_SEPARATOR, ESCAPE_ER + ESCAPED_LINE_SEPARATOR);
    }
    
	private void initVelocity() throws Exception {
		LOG.info("Inicializando Velocity");
		Properties prop = new Properties();
		prop.load(getClass().getResourceAsStream("/velocity.properties"));
		Velocity.init(prop);
		ctx = new VelocityContext();
        tplDatosUsuario = Velocity.getTemplate("datosUsuario.vm");
        tplDatosBravo = Velocity.getTemplate("datosBravo.vm");
	}
	
	private String mergeTemplate(Template tpl, VelocityContext ctx) {
		Writer writer = new StringWriter();

		try {
			tpl.merge(ctx, writer);
		} catch (Exception e) {
			LOG.error("Error al hacer el merge de la plantilla " + tpl.getName());
		}
		
		return writer.toString();
	}
}