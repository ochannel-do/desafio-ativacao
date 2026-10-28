package com.exemplo.ativacao.infrastructure.web;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.exemplo.ativacao.core.application.port.in.*;
import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.*;
import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import com.exemplo.ativacao.infrastructure.security.SegurancaConfiguration;
import com.exemplo.ativacao.support.Tokens;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SolicitacaoController.class)
@Import({ApiExceptionHandler.class, HttpRequestLoggingFilter.class, SegurancaConfiguration.class})
@TestPropertySource(
    properties = {
      "ativacao.seguranca.emissor=" + Tokens.EMISSOR,
      "ativacao.seguranca.audiencia=" + Tokens.AUDIENCIA,
      "ativacao.seguranca.chave-hmac=" + Tokens.CHAVE
    })
class SolicitacaoHttpTest {
  static JwtRequestPostProcessor escrita() {
    return token(SegurancaConfiguration.ESCOPO_ESCRITA);
  }

  static JwtRequestPostProcessor leitura() {
    return token(SegurancaConfiguration.ESCOPO_LEITURA);
  }

  static JwtRequestPostProcessor token(String escopo) {
    return jwt()
        .jwt(j -> j.claim("client_id", "sistema-a"))
        .authorities(new SimpleGrantedAuthority(escopo));
  }

  static final UUID ID = UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90");
  static final String JSON =
      """
      {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
      """;
  static final SolicitacaoView VIEW =
      new SolicitacaoView(
          ID,
          "EXT-12345",
          "CUST-10001",
          "INTERNET-500",
          StatusSolicitacao.PENDING,
          0,
          null,
          Instant.parse("2026-10-26T14:03:11Z"),
          Instant.parse("2026-10-26T14:03:11Z"));

  @Autowired MockMvc mvc;
  @MockitoBean RegistrarSolicitacaoUseCase registrar;
  @MockitoBean ConsultarSolicitacaoUseCase consultar;
  @MockitoBean ListarSolicitacoesUseCase listar;

  @Test
  void criacaoRetorna201ComLocationEDatasIso() throws Exception {
    when(registrar.executar(
            argThat(
                c ->
                    c.externalId().equals("EXT-12345")
                        && c.consumidor().equals("sistema-a")
                        && c.correlationId() != null)))
        .thenReturn(new Criada(VIEW));
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/requests/" + ID))
        .andExpect(jsonPath("$.id").value(ID.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"));
  }

  @Test
  void retryIdenticoRetorna200ComRegistroOriginal() throws Exception {
    when(registrar.executar(any())).thenReturn(new Repetida(VIEW));
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.id").value(ID.toString()));
  }

  @Test
  void conteudoDivergenteRetorna409() throws Exception {
    when(registrar.executar(any())).thenReturn(new Conflitante("EXT-12345"));
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/duplicate-conflict"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void dadosInvalidosListamCadaCampo() throws Exception {
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"externalId":"EXT 1","productCode":"%s"}
                    """
                        .formatted("P".repeat(65))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Dados inválidos"))
        .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(
            jsonPath("$.errors[*].field")
                .value(containsInAnyOrder("externalId", "customerId", "productCode")))
        .andExpect(jsonPath("$.errors[?(@.field=='customerId')].message").value("é obrigatório"));
    verifyNoInteractions(registrar);
  }

  @Test
  void jsonMalformadoRetorna400SemDetalhesInternos() throws Exception {
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("Jackson"))));
  }

  @Test
  void consultaExistenteENaoExistente() throws Exception {
    when(consultar.porId(ID, "sistema-a")).thenReturn(Optional.of(VIEW));
    mvc.perform(get("/api/v1/requests/{id}", ID).with(leitura()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    var outro = UUID.randomUUID();
    when(consultar.porId(outro, "sistema-a")).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/requests/{id}", outro).with(leitura()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/not-found"));
  }

  @Test
  void idMalformadoRetorna400() throws Exception {
    mvc.perform(get("/api/v1/requests/nao-e-uuid").with(leitura()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("id"));
  }

  @Test
  void listagemRepassaFiltrosEPaginacao() throws Exception {
    var filtro =
        new ListarSolicitacoesUseCase.Filtro(
            "sistema-a", StatusSolicitacao.PENDING, "CUST-1", 2, 10);
    when(listar.listar(filtro))
        .thenReturn(new ListarSolicitacoesUseCase.Pagina(List.of(VIEW), 2, 10, 21));
    mvc.perform(
            get("/api/v1/requests?status=PENDING&customerId=CUST-1&page=2&size=10").with(leitura()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
        .andExpect(jsonPath("$.totalElements").value(21))
        .andExpect(jsonPath("$.totalPages").value(3));
  }

  @Test
  void listagemRejeitaParametrosInvalidos() throws Exception {
    mvc.perform(get("/api/v1/requests?status=ACTIVE").with(leitura()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("status"));
    mvc.perform(get("/api/v1/requests?size=101").with(leitura()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("size"));
    verifyNoInteractions(listar);
  }

  @Test
  void metodoSemRegraDeAcessoEhNegado() throws Exception {
    mvc.perform(delete("/api/v1/requests/{id}", ID).with(escrita()))
        .andExpect(status().isForbidden());
  }

  @Test
  void erroInesperadoNaoVazaDetalhes() throws Exception {
    when(registrar.executar(any())).thenThrow(new IllegalStateException("senha=segredo"));
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Não foi possível processar a requisição."))
        .andExpect(content().string(not(containsString("segredo"))));
  }

  @Test
  void semTokenRetorna401EmProblemDetails() throws Exception {
    mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/unauthorized"));
    mvc.perform(get("/api/v1/requests/{id}", ID)).andExpect(status().isUnauthorized());
    verifyNoInteractions(registrar, consultar);
  }

  @Test
  void escopoInsuficienteRetorna403() throws Exception {
    mvc.perform(
            post("/api/v1/requests")
                .with(leitura())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/forbidden"));
    mvc.perform(get("/api/v1/requests/{id}", ID).with(escrita())).andExpect(status().isForbidden());
    verifyNoInteractions(registrar, consultar);
  }

  @Test
  void corpoAcimaDoLimiteRetorna413() throws Exception {
    mvc.perform(
            post("/api/v1/requests")
                .with(escrita())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"" + "A".repeat(20_000) + "\"}"))
        .andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(registrar);
  }
}
