<template>
    <section class="summary">
        <table class="brief_summary" v-if="this.sumario">
            <caption>
                Resum <small>- {{ Intl.DateTimeFormat('ca-ES', {
                    timeStyle: "short", dateStyle:
                        "medium"
                }).format(this.fechaActualizacion) }}</small>
            </caption>
            <tr>
                <th>Libros</th>
                <td align="right">
                    {{ Intl.NumberFormat('ca').format(this.sumario.libros) }}
                </td>
            </tr>
            <tr>
                <th>Autores</th>
                <td align="right">
                    {{ Intl.NumberFormat('ca').format(this.sumario.autores) }}
                </td>
            </tr>
            <tr>
                <th>Idiomas</th>
                <td align="right">
                    {{ Intl.NumberFormat('ca').format(this.sumario.idiomas) }}
                </td>
            </tr>
            <tr>
                <th>Gèneres</th>
                <td align="right">
                    {{ Intl.NumberFormat('ca').format(this.sumario.generos) }}
                </td>
            </tr>
        </table>
        <span></span>
        <div class="brief_summary">
            <b-button :class="this.$store.state.calibreIntegration ? 'button is-link' : 'button is-light'"
                :loading="actualizando" :disabled="!this.$store.state.calibreIntegration" @click="actualizar()">
                <b-icon pack="fa" :icon="this.$store.state.calibreIntegration ? 'check' : 'times'"></b-icon>
                <span>Integració amb Calibre</span>
            </b-button>
        </div>
        <div class="brief_summary" v-if="this.$store.state.eplReloadEnabled">
            <b-button :class="this.$store.state.eplReloadEnabled ? 'button is-warning' : 'button is-warning'"
                :loading="actualizando" @click="recargarDatos()">
                <b-icon pack="fa" :icon="this.$store.state.eplReloadEnabled ? 'check' : 'times'"></b-icon>
                <span>Recarrega la biblioteca</span>
            </b-button>
        </div>
    </section>
</template>

<script>
import Vue from "vue";
import axios from "axios";
import Vuex from "vuex";
import { EventBus } from '../event-bus';

Vue.use(Vuex);

export default {
    data() {
        return {
            sumario: null,
            fechaActualizacion: null,
            actualizando: false
        }
    },
    mounted() {
        axios
            .get('/librarian/sumario')
            .then(response => {
                this.sumario = response.data;
                this.fechaActualizacion = new Date(this.sumario.fechaActualizacion);
                this.$store.commit("changeVersion", this.sumario.buildVersion);
                this.$store.commit("changeLatestVersion", this.sumario.latestVersion);
                this.$store.commit("changeCalibreIntegration", this.sumario.integracionCalibreHabilitada);
                this.$store.commit("changeEplReloadEnabled", this.sumario.recargaEPLHabilitada);
                this.$store.commit("changeMiniaturasEnTabla", this.sumario.miniaturasEnTabla);
            })
            .catch(e => {
                this.$buefy.notification.open({
                    type: 'is-danger'
                    , duration: 5000
                    , message: 'Error en mostrar el resum: ' + e
                    , hasIcon: true
                })
                console.error(e)
            });
    },
    methods: {
        recargarDatos() {
            if (this.$store.state.eplReloadEnabled) {
                this.actualizando = true;
                axios.get('/librarian/updateData', { timeout: 60000 })
                    .then(() => {
                        this.actualizando = false;
                        EventBus.$emit('updatedData', 'Correct');
                        this.$buefy.notification.open({
                            type: 'is-info'
                            , duration: 3000
                            , message: 'S’han recarregat les dades de la biblioteca.'
                            , hasIcon: true
                        });
                    })
                    .catch(error => {
                        this.actualizando = false;
                        if(error.response.status === 304){
                            this.$buefy.notification.open({
                                type: 'is-warning'
                                , duration: 3000
                                , message: 'No s’han obtingut dades noves per a la biblioteca.'
                                , hasIcon: true
                            });
                        } else {
                            EventBus.$emit('updatedData', 'Error');
                            this.$buefy.notification.open({
                                type: 'is-error'
                                , duration: 3000
                                , message: 'Error en recarregar les dades de la biblioteca.'
                                , hasIcon: true
                            });
                            this.$nextTick(() => {
                                throw error;
                            });
                        }
                    });
            }
        },
        actualizar() {
            if (this.$store.state.calibreIntegration) {
                this.actualizando = true;
                axios.get('/librarian/updateCalibre')
                    .then(({ data }) => {
                        this.actualizando = false;
                        EventBus.$emit('updatedCalibre', 'Correct');
                        this.$buefy.notification.open({
                            type: 'is-info'
                            , duration: 3000
                            , message: 'La sincronització amb Calibre ha finalitzat correctament.'
                            , hasIcon: true
                        });
                    })
                    .catch(error => {
                        this.actualizando = false;
                        EventBus.$emit('updatedCalibre', 'Error');
                        this.$buefy.notification.open({
                            type: 'is-error'
                            , duration: 3000
                            , message: 'Error en sincronitzar amb Calibre.'
                            , hasIcon: true
                        });
                        this.$nextTick(() => {
                            throw error;
                        });
                    });
            }
        }
    },
}
</script>

<style scoped></style>
